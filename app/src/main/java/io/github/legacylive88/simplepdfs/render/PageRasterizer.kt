package io.github.legacylive88.simplepdfs.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
         * opens it, agrees on the page count and can draw the pages that were changed. This is
         * the gate every edit passes before it is allowed to replace the user's document.
         */
        val VERIFIER = SaveVerifier { file, expectedPageCount, touchedPages ->
            open(file).use { r ->
                if (r.pageCount != expectedPageCount) {
                    throw IOException("Page count changed from $expectedPageCount to ${r.pageCount}")
                }
                val probe = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                try {
                    for (page in touchedPages) {
                        val size = r.pageSize(page)
                        if (size[0] <= 0f || size[1] <= 0f) throw IOException("Page ${page + 1} has no size")
                        r.render(page, probe, 48f / size[0], 0f, 0f)
                    }
                } finally {
                    probe.recycle()
                }
            }
        }
    }
}
