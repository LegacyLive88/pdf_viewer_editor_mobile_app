package io.github.legacylive88.simplepdfs.render

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import io.github.legacylive88.simplepdfs.pdfcore.AnnotationKind
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Finds out, by trying, which kinds of annotation this device's PDF renderer draws.
 *
 * Android's renderer has changed over time (nothing before Android 15, form fields since) and
 * is now a system component that is updated independently of the Android version. Rather than
 * keep a table of versions, the app renders a tiny PDF of its own once per run: six black
 * squares, each of them an annotation of a different type on an otherwise white page. The
 * squares that come out black are the types the renderer draws.
 *
 * The result decides what the user is told when a document contains things that cannot be
 * shown (see `DocumentViewModel.warnAboutContentThatIsNotShown`).
 */
object AnnotationProbe {

    /** Side of one square in points, which the probe renders as pixels. */
    private const val CELL = 10

    /** Annotation subtype of each square, left to right. */
    internal val SUBTYPES = listOf("Widget", "Text", "FreeText", "Stamp", "Square", "Ink")

    @Volatile
    private var result: Set<AnnotationKind>? = null

    /**
     * The kinds of annotation the platform renderer draws. Measured on first use (a few
     * milliseconds, so not on the main thread) and remembered for the life of the process.
     * If the measurement itself fails, nothing is assumed to be drawn.
     *
     * @param scratchDir a writable directory for a temporary file
     */
    fun drawnKinds(scratchDir: File): Set<AnnotationKind> {
        result?.let { return it }
        synchronized(this) {
            result?.let { return it }
            val measured = try {
                measure(scratchDir)
            } catch (_: Throwable) {
                emptySet()
            }
            result = measured
            return measured
        }
    }

    private fun measure(dir: File): Set<AnnotationKind> {
        val file = File.createTempFile("probe", ".pdf", dir)
        try {
            file.writeBytes(document())
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Throwable) {
                pfd.close()
                throw e
            }
            renderer.use {
                renderer.openPage(0).use { page ->
                    val width = SUBTYPES.size * CELL
                    val bitmap = createBitmap(width, CELL)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        // exactly the call PlatformRasterizer.render makes
                        page.render(bitmap, null, Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val drawn = BooleanArray(SUBTYPES.size) { i ->
                            isDark(bitmap.getPixel(i * CELL + CELL / 2, CELL / 2))
                        }
                        return kindsFor(drawn)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    internal fun isDark(colour: Int): Boolean =
        ((colour shr 16) and 0xFF) + ((colour shr 8) and 0xFF) + (colour and 0xFF) < 3 * 128

    /**
     * Turns "which squares were drawn" (in the order of [SUBTYPES]) into annotation kinds.
     * A kind made of two subtypes only counts when both are drawn. [AnnotationKind.OTHER] is
     * never claimed: it stands for types the probe does not contain.
     */
    internal fun kindsFor(drawn: BooleanArray): Set<AnnotationKind> {
        require(drawn.size == SUBTYPES.size)
        val kinds = java.util.EnumSet.noneOf(AnnotationKind::class.java)
        if (drawn[0]) kinds.add(AnnotationKind.FORM_FIELD)
        if (drawn[1] && drawn[2]) kinds.add(AnnotationKind.NOTE)
        if (drawn[3]) kinds.add(AnnotationKind.STAMP)
        if (drawn[4] && drawn[5]) kinds.add(AnnotationKind.DRAWING)
        return kinds
    }

    /**
     * The probe document: one page, [SUBTYPES].size squares side by side, each an annotation
     * whose appearance is a solid black square. The form field is a proper member of the
     * document's form and also declares a black background, so it comes out black whether a
     * renderer uses its stored appearance or builds a new one.
     */
    internal fun document(): ByteArray {
        val n = SUBTYPES.size
        val firstAnnot = 5
        val appearance = firstAnnot + n
        val font = appearance + 1
        val refs = (0 until n).joinToString(" ") { "${firstAnnot + it} 0 R" }
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [$firstAnnot 0 R] " +
            "/DA (/Helv 0 Tf 0 g) /DR << /Font << /Helv $font 0 R >> >> >> >>"
        objects += "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n * CELL} $CELL] /Resources << >> " +
            "/Contents 4 0 R /Annots [$refs] >>"
        objects += stream("", "")
        for ((i, subtype) in SUBTYPES.withIndex()) {
            val left = i * CELL
            val common = "/Type /Annot /Subtype /$subtype /Rect [$left 0 ${left + CELL} $CELL] /F 4 /P 3 0 R " +
                "/AP << /N $appearance 0 R >>"
            objects += when (subtype) {
                "Widget" -> "<< $common /FT /Tx /T (probe) /DA (/Helv 0 Tf 0 g) /MK << /BG [0 0 0] /BC [0 0 0] >> >>"
                "Text" -> "<< $common /Contents (probe) /Name /Note >>"
                "FreeText" -> "<< $common /Contents (probe) /DA (0 g /Helv 6 Tf) >>"
                "Square" -> "<< $common /C [0 0 0] /IC [0 0 0] >>"
                "Ink" -> "<< $common /C [0 0 0] /InkList [[${left + 1} 1 ${left + CELL - 1} ${CELL - 1}]] >>"
                else -> "<< $common >>"
            }
        }
        objects += stream("/Type /XObject /Subtype /Form /BBox [0 0 $CELL $CELL] /Resources << >>", "0 g 0 0 $CELL $CELL re f")
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"

        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.7\n")
        val offsets = IntArray(objects.size)
        for ((i, body) in objects.withIndex()) {
            offsets[i] = out.size()
            write("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset))
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun stream(dictionary: String, data: String): String =
        "<< $dictionary /Length ${data.length} >>\nstream\n$data\nendstream"
}
