package io.github.legacylive88.simplepdfs.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.github.legacylive88.simplepdfs.pdfcore.PageChange
import io.github.legacylive88.simplepdfs.pdfcore.SaveVerifier
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Turns pages of a PDF file into pixels.
 *
 * The app talks to this small interface rather than to Android's `PdfRenderer` directly so the
 * rest of the code can be exercised in JVM tests, where the platform renderer does not exist.
 * Implementations need not be thread-safe; the caller uses one thread.
 */
interface PageRasterizer : Closeable {
    val pageCount: Int

    /** Page size in points as displayed (rotation applied). Returns width, height. */
    fun pageSize(pageIndex: Int): FloatArray

    /**
     * Draws part of a page into [bitmap] (ARGB_8888). The page is scaled by [scale] pixels per
     * point and then shifted so that page pixel ([offsetX], [offsetY]) lands on the bitmap's
     * top-left corner. Areas outside the page stay white.
     */
    fun render(pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float)

    fun interface Factory {
        @Throws(IOException::class, PasswordProtectedException::class)
        fun open(file: File): PageRasterizer
    }
}

/** The file needs a password, which this version does not support. */
class PasswordProtectedException : Exception("This PDF is password protected")

/**
 * [PageRasterizer] backed by the platform's `PdfRenderer` (PDFium). No PDF rendering code ships
 * inside the app; pages look exactly as they do in other Android apps on the same device.
 */
class PlatformRasterizer private constructor(private val renderer: PdfRenderer) : PageRasterizer {

    override val pageCount: Int = renderer.pageCount

    override fun pageSize(pageIndex: Int): FloatArray = renderer.openPage(pageIndex).use { page ->
        floatArrayOf(page.width.toFloat(), page.height.toFloat())
    }

    override fun render(pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) {
        // A PDF page has no background of its own.
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(pageIndex).use { page ->
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(-offsetX, -offsetY)
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        val FACTORY = PageRasterizer.Factory { file -> open(file) }

        @Throws(IOException::class, PasswordProtectedException::class)
        fun open(file: File): PlatformRasterizer {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                // On success the renderer owns the descriptor and closes it.
                return PlatformRasterizer(PdfRenderer(pfd))
            } catch (e: SecurityException) {
                closeQuietly(pfd)
                throw PasswordProtectedException()
            } catch (e: IOException) {
                closeQuietly(pfd)
                throw e
            } catch (e: Exception) {
                closeQuietly(pfd)
                throw IOException(e.message ?: "Cannot read this PDF", e)
            }
        }

        private fun closeQuietly(pfd: ParcelFileDescriptor) {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }

        /**
         * Accepts a file written by the engine only if the platform's own PDF implementation
         *  - opens it and agrees on the page count,
         *  - can draw every page that was touched, and
         *  - for a page whose content was rewritten, draws it **identically to before outside
         *    the edited area** (see [RenderComparison]).
         * This is the gate every edit passes before it may replace the user's document.
         */
        val VERIFIER = SaveVerifier { candidate, previous, expectedPageCount, changes ->
            open(candidate).use { after ->
                if (after.pageCount != expectedPageCount) {
                    throw IOException("Page count changed from $expectedPageCount to ${after.pageCount}")
                }
                var before: PlatformRasterizer? = null
                try {
                    for (change in changes) {
                        val size = after.pageSize(change.pageIndex)
                        if (size[0] <= 0f || size[1] <= 0f) throw IOException("Page ${change.pageIndex + 1} has no size")
                        if (!change.contentChanged) {
                            // Annotations do not alter how page content renders; just prove the page still draws.
                            val probe = createBitmap(48, 48)
                            try {
                                after.render(change.pageIndex, probe, 48f / size[0], 0f, 0f)
                            } finally {
                                probe.recycle()
                            }
                            continue
                        }
                        val old = before ?: open(previous).also { before = it }
                        comparePage(old, after, change, size)
                    }
                } finally {
                    before?.close()
                }
            }
        }

        private const val COMPARE_WIDTH = 520f
        private const val COMPARE_MAX_HEIGHT = 1400f

        private fun comparePage(before: PlatformRasterizer, after: PlatformRasterizer, change: PageChange, size: FloatArray) {
            val oldSize = before.pageSize(change.pageIndex)
            if (oldSize[0] != size[0] || oldSize[1] != size[1]) {
                throw IOException("Page ${change.pageIndex + 1} changed size")
            }
            val scale = minOf(COMPARE_WIDTH / size[0], COMPARE_MAX_HEIGHT / size[1])
            val width = maxOf(1, (size[0] * scale).toInt())
            val height = maxOf(1, (size[1] * scale).toInt())
            val a = IntArray(width * height)
            val b = IntArray(width * height)
            val bitmap = createBitmap(width, height)
            try {
                before.render(change.pageIndex, bitmap, scale, 0f, 0f)
                bitmap.getPixels(a, 0, width, 0, 0, width, height)
                after.render(change.pageIndex, bitmap, scale, 0f, 0f)
                bitmap.getPixels(b, 0, width, 0, 0, width, height)
            } finally {
                bitmap.recycle()
            }
            // The engine and the renderer normally agree on the page size; if not (a PDF
            // "UserUnit"), the change's coordinates are converted.
            val unitScale = if (change.pageSize.width > 0f && kotlin.math.abs(change.pageSize.width - size[0]) > 1.5f) {
                size[0] / change.pageSize.width
            } else {
                1f
            }
            val different = RenderComparison.differingPixels(a, b, width, height, RenderComparison.mask(change, scale, unitScale))
            if (different > RenderComparison.tolerated(width, height)) {
                throw IOException("The edit would also change other parts of page ${change.pageIndex + 1} ($different pixels differ)")
            }
        }
    }
}
