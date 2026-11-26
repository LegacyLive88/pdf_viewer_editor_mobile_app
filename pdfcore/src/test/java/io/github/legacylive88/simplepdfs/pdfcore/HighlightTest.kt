package io.github.legacylive88.simplepdfs.pdfcore

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/** Feature 5 (part 2): highlighting selected text, removing highlights, saving over the same file. */
@RunWith(RobolectricTestRunner::class)
class HighlightTest : PdfTestBase() {

    private fun highlights(s: PdfSession, page: Int) = s.markups(page).filter { it.kind == MarkupKind.HIGHLIGHT }

    private fun assertQuadsEqual(expected: List<Quad>, actual: List<Quad>, tolerance: Float = 0.02f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertTrue("box $i: expected $e but was $a",
                abs(e.left - a.left) <= tolerance && abs(e.top - a.top) <= tolerance &&
                    abs(e.right - a.right) <= tolerance && abs(e.bottom - a.bottom) <= tolerance)
        }
    }

    @Test
    fun highlightIsWrittenAsAStandardAnnotationAndSurvivesReopening() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val sel = selectWords(s, 0, "Sales", "trend")
        s.addHighlight(sel)

        // the session's own view
        val mine = highlights(s, 0)
        assertEquals(1, mine.size)
        assertQuadsEqual(sel.quads, mine[0].quads)
        assertEquals(0xFFEB3B, mine[0].colorRgb)

        // the original file is untouched, the new one starts with exactly the original bytes
        assertTrue(s.currentFile != original)
        assertTrue(sameBytesPrefix(original, s.currentFile))
        assertEquals(original.length(), s.unchangedPrefixLength)

        // a fresh session sees the same highlight
        val again = reopen(s.currentFile)
        val reread = highlights(again, 0)
        assertEquals(1, reread.size)
        assertQuadsEqual(sel.quads, reread[0].quads)
        assertTrue(highlights(again, 1).isEmpty())

        // and the annotation is a complete, standard one
        PDDocument.load(s.currentFile).use { doc ->
            val annots = doc.getPage(0).annotations
            assertEquals(1, annots.size)
            val d = annots[0].cosObject
            assertEquals("Highlight", d.getNameAsString(COSName.SUBTYPE))
            assertEquals(16, (d.getDictionaryObject(COSName.getPDFName("QuadPoints")) as com.tom_roush.pdfbox.cos.COSArray).size())
            assertTrue("has an appearance stream", annots[0].normalAppearanceStream != null)
            assertEquals(sel.text, d.getString(COSName.CONTENTS))
        }
        publish("highlighted", s.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf("Sales grew steadily", "Quarterly Report")),
            highlights = sel.quads.map { "0:${quadBox(it)}" }, highlightCount = mapOf(0 to 1, 1 to 0),
            untouchedPrefixOf = original, unchangedPages = listOf(0, 1))
    }

    @Test
    fun highlightingTheSameTextTwiceDoesNotStackHighlights() {
        val s = open("basic.pdf")
        s.addHighlight(selectWords(s, 0, "Sales", "trend"))
        s.addHighlight(selectWords(s, 0, "Sales", "trend"))
        assertEquals(1, highlights(s, 0).size)
        // a second, overlapping highlight replaces only the overlap
        s.addHighlight(selectWords(s, 0, "steadily", "region,"))
        val all = highlights(s, 0)
        assertEquals(2, all.size)
        publish("no-stacking", s.currentFile, pages = 2, highlightCount = mapOf(0 to 2))
    }

    @Test
    fun removingAHighlightFromPartOfItTrimsIt() {
        val s = open("basic.pdf")
        val whole = selectWords(s, 0, "Sales", "region,") // one line fragment
        s.addHighlight(whole)
        val middle = selectWords(s, 0, "steadily", "in")
        assertTrue(s.removeHighlights(middle))

        val left = highlights(s, 0)
        assertEquals("still one annotation", 1, left.size)
        val quads = left[0].quads.sortedBy { it.left }
        assertEquals("split into the part before and the part after", 2, quads.size)
        val full = whole.quads[0]
        val cut = middle.quads[0]
        assertEquals(full.left, quads[0].left, 0.05f)
        assertEquals(cut.left, quads[0].right, 0.05f)
        assertEquals(cut.right, quads[1].left, 0.05f)
        assertEquals(full.right, quads[1].right, 0.05f)

        val again = reopen(s.currentFile)
        assertEquals(2, highlights(again, 0)[0].quads.size)
        publish("trimmed", s.currentFile, pages = 2,
            highlights = quads.map { "0:${quadBox(it)}" }, highlightCount = mapOf(0 to 1))
    }

    @Test
    fun removingAllHighlightedTextDeletesTheAnnotationAndKeepsTheFileValid() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val sel = selectWords(s, 0, "Sales", "trend")
        s.addHighlight(sel)
        val withHighlight = s.currentFile.length()
        assertTrue(s.removeHighlights(sel))
        assertTrue(highlights(s, 0).isEmpty())

        // Because all changes of a session go into ONE update, removing the highlight again
        // makes the update smaller rather than piling up revisions.
        assertTrue(s.currentFile.length() < withHighlight)
        assertTrue(sameBytesPrefix(original, s.currentFile))

        // The update only rewrites existing objects here. The trailer /Size must still cover
        // every object of the document (readers ignore objects numbered beyond it).
        val tail = s.currentFile.readBytes().let { String(it, it.size - 600, 600, Charsets.ISO_8859_1) }
        val size = Regex("/Size\\s+(\\d+)").findAll(tail).last().groupValues[1].toInt()
        val originalSize = Regex("/Size\\s+(\\d+)").findAll(original.readText(Charsets.ISO_8859_1)).last().groupValues[1].toInt()
        assertTrue("trailer /Size $size must not be below $originalSize", size >= originalSize)

        val again = reopen(s.currentFile)
        assertTrue(highlights(again, 0).isEmpty())
        assertEquals("Quarterly Report", again.paragraphTexts(0)[0])
        publish("removed", s.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf("Sales grew steadily"), 1 to listOf("second page")),
            highlightCount = mapOf(0 to 0), untouchedPrefixOf = original)
    }

    @Test
    fun removingWhereNothingIsHighlightedChangesNothing() {
        val original = fixture("basic.pdf")
        val s = open(original)
        assertFalse(s.removeHighlights(selectWords(s, 0, "Sales", "trend")))
        assertEquals("no file was written", original, s.currentFile)
        s.addHighlight(selectWords(s, 0, "Sales", "grew"))
        val before = s.currentFile.readBytes()
        assertFalse(s.removeHighlights(selectWords(s, 0, "forecasting.", "forecasting.")))
        assertTrue(before.contentEquals(s.currentFile.readBytes()))
    }

    @Test
    fun highlightsFromOtherApplicationsAreShownAndCanBeRemoved() {
        val s = open("annotated.pdf")
        val all = s.markups(0)
        // three visible highlights (standard order, ring order, Rect only), one underline; the hidden one is skipped
        assertEquals(3, all.count { it.kind == MarkupKind.HIGHLIGHT })
        assertEquals(1, all.count { it.kind == MarkupKind.UNDERLINE })
        val first = all.first { it.kind == MarkupKind.HIGHLIGHT && it.colorRgb == 0xFFFF00 }
        assertEquals(72f, first.quads[0].left, 0.1f)
        assertEquals(174f, first.quads[0].right, 0.1f)
        // ring-ordered quad points still give a proper box
        val green = all.first { it.kind == MarkupKind.HIGHLIGHT && it.colorRgb == 0x80FF80 }
        assertEquals(72f, green.quads[0].left, 0.1f)
        assertEquals(250f, green.quads[0].right, 0.1f)
        assertTrue(green.quads[0].bottom - green.quads[0].top in 13f..15f)

        // Remove the first highlight completely, trim the green one, leave the underline alone.
        assertTrue(s.removeHighlights(selectWords(s, 0, "The", "paragraph")))
        assertTrue(s.removeHighlights(selectWords(s, 0, "third", "quarter.")))
        val after = s.markups(0)
        assertEquals(2, after.count { it.kind == MarkupKind.HIGHLIGHT })
        assertEquals(1, after.count { it.kind == MarkupKind.UNDERLINE })
        val greenAfter = after.first { it.kind == MarkupKind.HIGHLIGHT && it.colorRgb == 0x80FF80 }
        assertTrue("green highlight now starts after 'third quarter.'", greenAfter.quads[0].left > 140f)

        val again = reopen(s.currentFile)
        assertEquals(2, again.markups(0).count { it.kind == MarkupKind.HIGHLIGHT })
        publish("foreign", s.currentFile, pages = 2, highlightCount = mapOf(0 to 2))
    }

    @Test
    fun highlightsOnRotatedAndOffsetPagesLandOnTheText() {
        val original = fixture("rotated.pdf")
        val s = open(original)
        val expected = ArrayList<String>()
        val counts = HashMap<Int, Int>()
        for (page in 0 until s.pageCount) {
            val first = if (page == 5) "Offset" else "Rotated"
            val last = if (page == 5) "line" else "it."
            val sel = selectWords(s, page, first, last)
            s.addHighlight(sel)
            assertQuadsEqual(sel.quads, highlights(s, page)[0].quads)
            sel.quads.forEach { expected.add("$page:${quadBox(it)}") }
            counts[page] = 1
        }
        val again = reopen(s.currentFile)
        for (page in 0 until again.pageCount) assertEquals(1, highlights(again, page).size)
        // a highlight is an annotation: the page content itself must render exactly as before
        publish("rotated", s.currentFile, pages = 7, highlights = expected, highlightCount = counts, untouchedPrefixOf = original,
            unchangedPages = (0 until 7).toList())
    }

    @Test
    fun aSelectionMadeBeforeThePageChangedIsRefused() {
        val s = open("basic.pdf")
        val sel = selectWords(s, 0, "Sales", "trend")
        s.replaceParagraph(paragraphWith(s, 0, "Sales"), "Completely new text.")
        try {
            s.addHighlight(sel)
            fail("stale selection must be refused")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.STALE, e.reason)
        }
    }

    @Test
    fun highlightsWorkOnEveryFileStructure() {
        for (name in listOf("xref_stream.pdf", "linearized.pdf", "incremental.pdf", "lo_letter.pdf",
            "chrome_article.pdf", "cairo.pdf", "fpdf_unicode.pdf", "matplotlib_type3.pdf", "form_shared.pdf")) {
            if (!fixtureExists(name)) continue
            val original = fixture(name)
            val s = open(original)
            val words = s.wordBoxes(0)
            val a = words[1].second
            val b = words[minOf(8, words.size - 1)].second
            val sel = s.select(0, PdfPoint((a.left + a.right) / 2, (a.top + a.bottom) / 2),
                PdfPoint((b.left + b.right) / 2, (b.top + b.bottom) / 2))!!
            s.addHighlight(sel)
            assertTrue(name, sameBytesPrefix(original, s.currentFile))
            val again = reopen(s.currentFile)
            assertEquals(name, 1, highlights(again, 0).size)
            assertEquals(name, s.paragraphTexts(0), again.paragraphTexts(0))
            publish(name.removeSuffix(".pdf"), s.currentFile, pages = s.pageCount,
                highlights = sel.quads.map { "0:${quadBox(it)}" }, highlightCount = mapOf(0 to 1),
                untouchedPrefixOf = original, unchangedPages = (0 until minOf(s.pageCount, 3)).toList())
        }
    }

    @Test
    fun aFailedVerificationRollsTheEditBack() {
        val original = fixture("basic.pdf")
        var reject = false
        val verifier = SaveVerifier { candidate, previous, pages, changes ->
            if (reject) throw IllegalStateException("simulated: another PDF reader cannot open this")
            reloadVerifier.verify(candidate, previous, pages, changes)
        }
        val s = open(original, verifier)
        s.addHighlight(selectWords(s, 0, "Sales", "grew"))
        val good = s.currentFile.readBytes()

        reject = true
        try {
            s.addHighlight(selectWords(s, 0, "forecasting.", "forecasting."))
            fail("the edit must fail")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.VERIFICATION_FAILED, e.reason)
        }
        // The document is exactly what it was before the failed edit, on disk and in memory.
        assertTrue(good.contentEquals(s.currentFile.readBytes()))
        assertEquals(1, highlights(s, 0).size)
        assertTrue(sameBytesPrefix(original, s.currentFile))

        // And the session keeps working afterwards.
        reject = false
        s.addHighlight(selectWords(s, 0, "forecasting.", "forecasting."))
        assertEquals(2, highlights(s, 0).size)
        val again = reopen(s.currentFile)
        assertEquals(2, highlights(again, 0).size)
        publish("after-rollback", s.currentFile, pages = 2, highlightCount = mapOf(0 to 2), untouchedPrefixOf = original)
    }

    @Test
    fun copyToWritesTheCurrentDocument() {
        val s = open("basic.pdf")
        s.addHighlight(selectWords(s, 0, "Sales", "grew"))
        val copy = File(tmp.newFolder(), "copy.pdf")
        s.copyTo(copy)
        assertTrue(copy.readBytes().contentEquals(s.currentFile.readBytes()))
        assertEquals(1, highlights(reopen(copy), 0).size)
    }
}
