package io.github.legacylive88.simplepdfs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import io.github.legacylive88.simplepdfs.pdfcore.SaveVerifier
import io.github.legacylive88.simplepdfs.render.PageRasterizer
import io.github.legacylive88.simplepdfs.render.PasswordProtectedException
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Stand-in for the platform renderer, which does not exist on the JVM. It reports the real
 * page sizes of the file (read with PDFBox) and "draws" blank pages, and it records what it
 * was asked to do so tests can check the app re-reads the file after an edit.
 */
class FakeRasterizer(val file: File, private val sizes: List<FloatArray>, private val log: MutableList<String>) : PageRasterizer {
    override val pageCount: Int get() = sizes.size
    override fun pageSize(pageIndex: Int): FloatArray = sizes[pageIndex]
    override fun render(pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) {
        bitmap.eraseColor(Color.WHITE)
        synchronized(log) { log.add("render $pageIndex") }
    }
    override fun close() {
        synchronized(log) { log.add("close ${file.name}") }
    }

    class Factory : PageRasterizer.Factory {
        val log: MutableList<String> = ArrayList()
        override fun open(file: File): PageRasterizer {
            val sizes = try {
                PDDocument.load(file).use { doc ->
                    (0 until doc.numberOfPages).map { i ->
                        val page = doc.getPage(i)
                        val box = page.cropBox
                        val rotated = page.rotation % 180 != 0
                        // the platform renderer reports whole points
                        val w = (if (rotated) box.height else box.width).toInt().toFloat()
                        val h = (if (rotated) box.width else box.height).toInt().toFloat()
                        floatArrayOf(w, h)
                    }
                }
            } catch (e: InvalidPasswordException) {
                throw PasswordProtectedException()
            } catch (e: Exception) {
                throw IOException("not a PDF", e)
            }
            synchronized(log) { log.add("open ${file.name}") }
            return FakeRasterizer(file, sizes, log)
        }
    }
}

/** Like the app's verifier, but using PDFBox to re-read the written file. */
val reloadVerifier = SaveVerifier { candidate, _, expectedPageCount, changes ->
    PDDocument.load(candidate).use { doc ->
        check(doc.numberOfPages == expectedPageCount)
        for (c in changes) doc.getPage(c.pageIndex).contents.use { it.readBytes() }
    }
}

/** Runs queued main-thread work until [condition] holds. */
fun awaitMain(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
    if (!condition()) throw AssertionError("Timed out waiting for: $what")
}

/**
 * A content provider that serves one file and can be told to refuse writing, like a mail
 * attachment or a download handed over read-only.
 */
class TestDocumentProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "io.github.legacylive88.simplepdfs.testdocs"
        @JvmStatic var file: File? = null
        @JvmStatic var writable = true
        @JvmStatic var displayName = "Attachment.pdf"
        @JvmStatic var seekable = true
        fun uri(): Uri = Uri.parse("content://$AUTHORITY/doc")
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any?>(displayName, file?.length() ?: 0L))
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file ?: throw FileNotFoundException()
        if (mode != "r" && !writable) throw SecurityException("read-only document")
        if (mode.contains("rw") && !mode.contains('t') && !seekable) throw FileNotFoundException("no random access")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
