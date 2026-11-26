package io.github.legacylive88.simplepdfs.render

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Creates platform renderers, working around a defect of Android 7.
 *
 * On Android 7.0 and 7.1 a `PdfRenderer` whose creation fails – the file is damaged or locked
 * – is nevertheless "closed" later, when the garbage collector finalizes the half-built
 * object. The platform counts its open documents to decide when to start and stop its PDF
 * library (PDFium), and that stray close takes one off the count. From then on the count is
 * one too low: the library is shut down while a document is still open, or is not started for
 * the next one, and the app dies in native code – typically when the *next*, perfectly good
 * document is opened. Android 8 fixed this.
 *
 * The remedy is to hand the lost count back. After every failed creation, one extra renderer
 * on a tiny valid document is opened and never closed. Two more are opened before the first
 * use, so that the count cannot reach zero in the moment between a failure and its
 * compensation (the finalizer may run at any time). The cost is a few kilobytes and one file
 * descriptor per file that failed to open, for the life of the process.
 *
 * Found by the on-device tests on an Android 7.0 emulator; guarded by
 * `ViewerDeviceTest.afterFilesThatCannotBeOpenedTheNextDocumentStillOpens`.
 */
internal object RendererFactory {

    /** Renderers kept open and referenced for good; see above. */
    private val keptAlive = ArrayList<PdfRenderer>()
    private var prepared = false

    private const val SPARE = 2

    private val affected: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

    /**
     * Like `PdfRenderer(descriptor)`, and throws what it throws.
     *
     * @param scratchDir a writable directory (used on Android 7 only)
     */
    @Synchronized
    fun create(descriptor: ParcelFileDescriptor, scratchDir: File): PdfRenderer {
        if (!affected) return PdfRenderer(descriptor)
        if (!prepared) {
            prepared = true
            repeat(SPARE) { keepOneAlive(scratchDir) }
        }
        try {
            return PdfRenderer(descriptor)
        } catch (e: Throwable) {
            keepOneAlive(scratchDir)
            throw e
        }
    }

    /** How many renderers are being kept open; for tests. */
    @Synchronized
    fun keptAliveCount(): Int = keptAlive.size

    private fun keepOneAlive(dir: File) {
        try {
            val file = File(dir, "renderer-keepalive.pdf")
            if (file.length() == 0L) file.writeBytes(AnnotationProbe.document())
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                keptAlive.add(PdfRenderer(descriptor))
            } catch (e: Throwable) {
                // Should not happen with a document of our own making; nothing more can be done.
                descriptor.close()
            }
        } catch (_: Throwable) {
            // No writable place or no descriptor left: the platform defect stays unmitigated.
        }
    }
}
