package io.github.legacylive88.simplepdfs.pdfcore

import io.github.legacylive88.simplepdfs.pdfcore.internal.LayoutAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Feature 5 (part 1): selecting the text between two tapped points. */
@RunWith(RobolectricTestRunner::class)
class SelectionTest : PdfTestBase() {

    @Test
    fun selectsTheTextBetweenTwoPointsInReadingOrder() {
        val s = open("basic.pdf")
        val sel = selectWords(s, 0, "Sales", "trend")
        assertEquals("Sales grew steadily in every region, and the team expects the trend", sel.text)
        // one box per line: rest of line 2, start of line 3
        assertEquals(2, sel.quads.size)
        val (first, second) = sel.quads
        assertTrue("second box is below the first", second.top > first.top + 10f)
        assertEquals("line 3 starts at the left margin", 72f, second.left, 0.5f)
        assertTrue("line 2 selection starts after 'third quarter.'", first.left > 140f)
    }

    @Test
    fun theOrderOfTheTwoTapsDoesNotMatter() {
        val s = open("basic.pdf")
        val forward = selectWords(s, 0, "Sales", "trend")
        val backward = selectWords(s, 0, "trend", "Sales")
        assertEquals(forward.text, backward.text)
        assertEquals(forward.quads, backward.quads)
    }

    @Test
    fun tappingTheSameSpotTwiceSelectsThatWord() {
        val s = open("basic.pdf")
        val p = pointOf(s, 0, "steadily")
        val sel = s.select(0, p, p)!!
        assertEquals("steadily", sel.text)
        assertEquals(1, sel.quads.size)
        assertTrue(sel.quads[0].contains(p.x, p.y))
    }

    @Test
    fun wholeWordsAreSelectedEvenWhenTheTapHitsTheMiddleOfAWord() {
        val s = open("basic.pdf")
        val a = s.wordBoxes(0).first { it.first == "steadily" }.second
        val b = s.wordBoxes(0).first { it.first == "region," }.second
        // tap near the end of "steadily" and near the start of "region,"
        val sel = s.select(0, PdfPoint(a.right - 1f, (a.top + a.bottom) / 2), PdfPoint(b.left + 1f, (b.top + b.bottom) / 2))!!
        assertEquals("steadily in every region,", sel.text)
    }

    @Test
    fun aTapInEmptySpaceSnapsToTheNearestText() {
        val s = open("basic.pdf")
        // far right of the title line and well below the last body line
        val sel = s.select(0, PdfPoint(600f, 66f), PdfPoint(300f, 500f))
        assertNotNull(sel)
        assertTrue(sel!!.text, sel.text.startsWith("Report"))
        assertTrue(sel.text, sel.text.contains("Publish the roadmap"))
    }

    @Test
    fun selectionAcrossParagraphsKeepsParagraphBreaks() {
        val s = open("basic.pdf")
        val sel = selectWords(s, 0, "surprises.", "blank")
        assertEquals("surprises.\nA second paragraph follows after a blank", sel.text)
    }

    @Test
    fun selectionInsideAColumnStaysInThatColumn() {
        val s = open("columns.pdf")
        val sel = selectWords(s, 0, "The", "well.") // first word of the left column to its last word
        assertTrue(sel.text.startsWith("The left column is written first."))
        assertTrue(sel.text.endsWith("and cherries as well."))
        assertFalse(sel.text, sel.text.contains("boats"))
        assertFalse(sel.text, sel.text.contains("right column"))
        for (q in sel.quads) assertTrue("box stays in the left column: $q", q.right < 300f)
    }

    @Test
    fun selectionOnRotatedPagesHasBoxesOnTheText() {
        val s = open("rotated.pdf")
        for (page in 0 until s.pageCount) {
            val first = if (page == 5) "Offset" else "Rotated"
            val last = if (page == 5) "line" else "it."
            val sel = selectWords(s, page, first, last)
            assertEquals(2, sel.quads.size)
            val size = s.pageSize(page)
            for (q in sel.quads) {
                assertTrue("page $page $q", q.left >= 0f && q.top >= 0f && q.right <= size.width && q.bottom <= size.height)
            }
            val expected = if (page == 5) "Offset media box text second offset line" else "Rotated page text line one and a second line below it."
            assertEquals("page $page", expected, sel.text)
        }
    }

    @Test
    fun wordsBrokenAcrossLinesAreRejoined() {
        assertEquals("an example of text", LayoutAnalyzer.joinLines(listOf("an exam-", "ple of text")))
        // a hyphen before a capital letter or a digit is a real hyphen
        assertEquals("the Anglo- Saxon age", LayoutAnalyzer.joinLines(listOf("the Anglo-", "Saxon age")))
        assertEquals("plain lines are joined with a space", LayoutAnalyzer.joinLines(listOf("plain lines are", "joined with a space")))
        assertEquals("a dash - stays", LayoutAnalyzer.joinLines(listOf("a dash -", "stays")))
        assertEquals("", LayoutAnalyzer.joinLines(emptyList()))
    }

    @Test
    fun wordsAndPhrasesCanBeFound() {
        val s = open("basic.pdf")
        val the = s.findText(0, "the")
        assertEquals("'the' and 'The' as whole words", 9, the.size)
        assertEquals(8, s.findText(0, "the", ignoreCase = false).size)
        assertEquals(1, s.findText(0, "The", ignoreCase = false).size)
        assertTrue(s.findText(0, "th").isEmpty()) // only whole words
        // a phrase that runs over a line break gives one hit with two boxes
        val phrase = s.findText(0, "the team expects the trend")
        assertEquals(1, phrase.size)
        assertEquals("the team expects the trend", phrase[0].text)
        assertEquals(2, phrase[0].quads.size)
        // a hit behaves like any other selection
        s.addHighlight(phrase[0])
        assertEquals(1, s.markups(0).size)
        assertTrue(s.findText(0, "   ").isEmpty())
        assertTrue(s.findText(1, "Quarterly").isEmpty())
    }

    @Test
    fun selectionWorksOnEveryProducer() {
        for (name in listOf("lo_letter.pdf", "chrome_article.pdf", "cairo.pdf")) {
            if (!fixtureExists(name)) continue
            val s = open(name)
            val sel = selectWords(s, 0, "chairperson", "statements")
            assertEquals(name, "chairperson opened the meeting with a short welcome and then presented the financial statements", sel.text)
        }
    }
}
