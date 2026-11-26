package io.github.legacylive88.simplepdfs

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.legacylive88.simplepdfs.pdfcore.AnnotationKind
import io.github.legacylive88.simplepdfs.render.AnnotationProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The little PDF the app renders to find out what the device's renderer draws, and how the
 * result is read. The rendering itself needs a real device and is covered by
 * `ViewerDeviceTest.aDocumentWithFormFieldsOrStampsNamesWhatCannotBeShown`; the document is
 * additionally checked by `tools/cross_validate.py` with qpdf and PDFium.
 */
@RunWith(RobolectricTestRunner::class)
class AnnotationProbeTest {

    private val bytes = AnnotationProbe.document()
    private val text = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun theProbeIsAStrictlyWellFormedPdf() {
        assertTrue(text.startsWith("%PDF-1.7\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        // the cross-reference table points exactly at every object and at itself
        val xref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n0 13\n0000000000 65535 f \n", xref))
        val entries = text.substring(xref).lines().drop(3).take(12)
        for ((i, entry) in entries.withIndex()) {
            assertEquals("each entry is 20 bytes long", 19, entry.length) // plus the line feed
            assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj\n", entry.take(10).toInt()))
        }
        assertTrue(text.contains("trailer\n<< /Size 13 /Root 1 0 R >>"))
        // the stream lengths are right
        for (m in Regex("/Length (\\d+) >>\nstream\n").findAll(text)) {
            val start = m.range.last + 1
            assertTrue(text.startsWith("\nendstream", start + m.groupValues[1].toInt()))
        }
        // keep a copy for the independent check (tools/cross_validate.py)
        File("build/probe").mkdirs()
        File("build/probe/annotation-probe.pdf").writeBytes(bytes)
    }

    @Test
    fun itHoldsOneAnnotationOfEachTypeSideBySideAndTheFieldBelongsToTheForm() {
        PDDocument.load(bytes).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertEquals(60f, page.mediaBox.width, 0f)
            assertEquals(10f, page.mediaBox.height, 0f)
            val annotations = page.annotations
            assertEquals(
                listOf("Widget", "Text", "FreeText", "Stamp", "Square", "Ink"),
                annotations.map { it.subtype },
            )
            for ((i, a) in annotations.withIndex()) {
                assertEquals("square $i starts at", i * 10f, a.rectangle.lowerLeftX, 0f)
                assertEquals(10f, a.rectangle.width, 0f)
                assertEquals(10f, a.rectangle.height, 0f)
                assertFalse("must not be hidden", a.isHidden || a.isNoView)
                val appearance = a.normalAppearanceStream
                assertEquals("0 g 0 0 10 10 re f", String(appearance.cosObject.createInputStream().readBytes()))
            }
            val fields = doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM)
                .let { it as com.tom_roush.pdfbox.cos.COSDictionary }
                .getDictionaryObject(COSName.FIELDS) as com.tom_roush.pdfbox.cos.COSArray
            assertEquals(1, fields.size())
            assertTrue(fields.getObject(0) === annotations[0].cosObject)
        }
    }

    @Test
    fun blackSquaresAreReadAsTheKindsThatAreDrawn() {
        fun kinds(vararg drawn: Boolean) = AnnotationProbe.kindsFor(drawn)
        // Android 14 and older: nothing
        assertEquals(emptySet<AnnotationKind>(), kinds(false, false, false, false, false, false))
        // Android 16: only the form field
        assertEquals(setOf(AnnotationKind.FORM_FIELD), kinds(true, false, false, false, false, false))
        // a renderer that draws everything
        assertEquals(
            setOf(AnnotationKind.FORM_FIELD, AnnotationKind.NOTE, AnnotationKind.STAMP, AnnotationKind.DRAWING),
            kinds(true, true, true, true, true, true),
        )
        // a kind made of two types counts only when both are drawn
        assertEquals(setOf(AnnotationKind.STAMP), kinds(false, true, false, true, true, false))
        // types the probe does not contain are never claimed
        assertFalse(AnnotationKind.OTHER in kinds(true, true, true, true, true, true))

        assertTrue(AnnotationProbe.isDark(0xFF000000.toInt()))
        assertTrue(AnnotationProbe.isDark(0xFF303030.toInt()))
        assertFalse(AnnotationProbe.isDark(0xFFFFFFFF.toInt()))
        assertFalse(AnnotationProbe.isDark(0xFFE0E0E0.toInt()))
    }
}
