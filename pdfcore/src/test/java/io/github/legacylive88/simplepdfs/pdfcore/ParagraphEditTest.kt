package io.github.legacylive88.simplepdfs.pdfcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/** Feature 6: replacing the text of a paragraph and saving over the same file. */
@RunWith(RobolectricTestRunner::class)
class ParagraphEditTest : PdfTestBase() {

    private val firstParagraph = "The first paragraph of this document talks about revenue for the third quarter. Sales grew " +
        "steadily in every region, and the team expects the trend to continue into the next financial " +
        "year without any major surprises."

    /** Word boxes of everything except the paragraphs whose text starts with one of [except]. */
    private fun otherWords(s: PdfSession, page: Int, vararg except: String): List<Pair<String, Quad>> {
        val out = ArrayList<Pair<String, Quad>>()
        val found = HashSet<String>()
        for ((w, q) in s.wordBoxes(page)) {
            val info = s.paragraphAt(page, PdfPoint((q.left + q.right) / 2, (q.top + q.bottom) / 2))
            val prefix = except.firstOrNull { info != null && info.text.startsWith(it) }
            if (prefix != null) found.add(prefix) else out.add(w to q)
        }
        for (e in except) assertTrue("no paragraph starts with '$e' on page $page: ${s.paragraphTexts(page)}", e in found)
        return out
    }

    /** True when the page contains [text], ignoring how it is broken into lines and paragraphs. */
    private fun pageHas(s: PdfSession, page: Int, text: String): Boolean =
        s.pageTextContent(page).replace('\n', ' ').contains(text)

    /** Where the first line of a paragraph sits: the start of its baseline, near enough. */
    private fun anchor(p: ParagraphInfo): Pair<Float, Float> = Pair(p.quads[0].x4, p.quads[0].y4)

    private fun assertSameAnchor(message: String, expected: ParagraphInfo, actual: ParagraphInfo) {
        // Compared at the bottom of the line box: the top depends on the font's ascent, which
        // differs between the original font and the one the text is re-typeset in.
        val (ex, ey) = anchor(expected)
        val (ax, ay) = anchor(actual)
        assertTrue("$message: first line moved from ($ex, $ey) to ($ax, $ay)", abs(ex - ax) <= 0.6f && abs(ey - ay) <= 0.6f)
    }

    private fun assertSameWords(expected: List<Pair<String, Quad>>, actual: List<Pair<String, Quad>>, tolerance: Float = 0.05f) {
        assertEquals(expected.map { it.first }, actual.map { it.first })
        for (i in expected.indices) {
            val e = expected[i].second
            val a = actual[i].second
            assertTrue("'${expected[i].first}' moved: $e -> $a",
                abs(e.left - a.left) <= tolerance && abs(e.top - a.top) <= tolerance &&
                    abs(e.right - a.right) <= tolerance && abs(e.bottom - a.bottom) <= tolerance)
        }
    }

    @Test
    fun replacesAParagraphAndLeavesEverythingElseExactlyWhereItWas() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val para = paragraphWith(s, 0, "Sales")
        assertEquals(firstParagraph, para.text)
        assertTrue(para.editable)
        assertEquals("Liberation Sans", para.replacementFontName)
        assertEquals(12f, para.fontSize, 0.01f)
        assertEquals(4, para.quads.size)
        val before = otherWords(s, 0, "The first paragraph")

        val newText = "This paragraph was rewritten in the app. It is a little shorter than the one it replaces."
        val result = s.replaceParagraph(para, newText)
        assertTrue(result.changed)
        assertEquals(1f, result.fontScale, 0.001f)
        assertFalse(result.overflowed)
        assertEquals(2, result.lineCount)

        // the session's own view after the edit
        assertTrue(s.paragraphTexts(0).contains(newText))
        assertFalse(s.pageTextContent(0).contains("revenue"))
        assertSameWords(before, otherWords(s, 0, "This paragraph was rewritten"))
        // the new paragraph starts where the old one started
        val now = paragraphWith(s, 0, "rewritten")
        assertSameAnchor("page 0", para, now)
        assertEquals(12f, now.fontSize, 0.01f)
        // and it is still read in its place: after the date, before the second paragraph
        val order = s.paragraphTexts(0)
        assertEquals(order.indexOf("26 November 2026") + 1, order.indexOf(newText))
        assertTrue(order[order.indexOf(newText) + 1].startsWith("A second paragraph follows"))
        assertEquals("selection runs through the edited paragraph in reading order",
            "2026\n$newText\nA second", selectWords(s, 0, "2026", "second").text)

        // on disk: original bytes untouched, change appended
        assertTrue(sameBytesPrefix(original, s.currentFile))
        val again = reopen(s.currentFile)
        assertTrue(again.paragraphTexts(0).contains(newText))
        assertSameWords(before, otherWords(again, 0, "This paragraph was rewritten"))
        assertEquals(listOf("The second page has a single line of text.", "Page 2 of 2"), again.paragraphTexts(1))

        publish("replaced", s.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf(newText, "Quarterly Report", "A second paragraph follows", "Publish the roadmap"),
                1 to listOf("The second page has a single line of text.")),
            mustNotContain = mapOf(0 to listOf("revenue", "Sales grew steadily", "major surprises")),
            untouchedPrefixOf = original, changedAreas = areasOf(para, now), unchangedPages = listOf(1))
    }

    @Test
    fun savingUnchangedTextWritesNothing() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val para = paragraphWith(s, 0, "Sales")
        val result = s.replaceParagraph(para, "  " + para.text.replace(" ", "  ") + "\n")
        assertFalse(result.changed)
        assertEquals(original, s.currentFile)
    }

    @Test
    fun aParagraphCanBeEditedAgainInTheSameSessionAndInALaterOne() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val second = "Second rewrite, which is the one that should remain in the file."
        s.replaceParagraph(paragraphWith(s, 0, "Sales"), "First rewrite of the paragraph.")
        s.replaceParagraph(paragraphWith(s, 0, "rewrite"), second)
        assertTrue(s.paragraphTexts(0).contains(second))
        assertFalse(s.pageTextContent(0).contains("First rewrite"))
        assertTrue(sameBytesPrefix(original, s.currentFile))
        // All changes of a session go into ONE update, so the first rewrite leaves nothing
        // behind: the file is about as big as if only the second edit had been made.
        val direct = open(fixture("basic.pdf"))
        direct.replaceParagraph(paragraphWith(direct, 0, "Sales"), second)
        val difference = s.currentFile.length() - direct.currentFile.length()
        assertTrue("two edits cost $difference bytes more than one", difference in -200..700)

        val later = reopen(s.currentFile)
        later.replaceParagraph(paragraphWith(later, 0, "Second"), "Third rewrite made after reopening the document.")
        assertTrue(later.paragraphTexts(0).contains("Third rewrite made after reopening the document."))
        assertFalse(later.pageTextContent(0).contains("Second rewrite"))
        val last = reopen(later.currentFile)
        assertTrue(last.paragraphTexts(0).contains("Third rewrite made after reopening the document."))
        assertTrue(last.paragraphTexts(0).contains("Quarterly Report"))
        publish("edited-three-times", later.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf("Third rewrite made after reopening the document.", "A second paragraph follows")),
            mustNotContain = mapOf(0 to listOf("First rewrite", "Second rewrite", "revenue")))
    }

    @Test
    fun severalParagraphsAndHighlightsCanBeChangedInOneSession() {
        val original = fixture("basic.pdf")
        val s = open(original)
        s.replaceParagraph(paragraphWith(s, 0, "Quarterly"), "Annual Report")
        s.addHighlight(selectWords(s, 0, "second", "blank"))
        s.replaceParagraph(paragraphWith(s, 0, "Hire"), "• Hire three engineers")
        s.replaceParagraph(paragraphWith(s, 1, "single"), "The second page now says something else entirely.")
        val p0 = s.paragraphTexts(0)
        assertTrue(p0.contains("Annual Report"))
        assertTrue(p0.contains("• Hire three engineers"))
        assertTrue(p0.contains("• Review the budget"))
        assertEquals(1, s.markups(0).size)
        assertTrue(sameBytesPrefix(original, s.currentFile))

        val again = reopen(s.currentFile)
        assertTrue(again.paragraphTexts(0).contains("Annual Report"))
        assertTrue(again.paragraphTexts(1).contains("The second page now says something else entirely."))
        assertEquals(1, again.markups(0).size)
        publish("mixed", s.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf("Annual Report", "Hire three engineers", "Review the budget"),
                1 to listOf("The second page now says something else entirely.")),
            mustNotContain = mapOf(0 to listOf("Quarterly", "two engineers"), 1 to listOf("single line")),
            highlightCount = mapOf(0 to 1), untouchedPrefixOf = original)
    }

    @Test
    fun aCentredTitleStaysCentredAndARightAlignedLineStaysRightAligned() {
        val s = open("basic.pdf")
        val title = paragraphWith(s, 0, "Quarterly")
        val centreBefore = (title.left + title.right) / 2
        s.replaceParagraph(title, "Report")
        val after = paragraphWith(s, 0, "Report")
        assertEquals(centreBefore, (after.left + after.right) / 2, 1.0f)
        assertEquals(20f, after.fontSize, 0.01f)
        assertEquals("Liberation Sans Bold", after.replacementFontName)

        val date = paragraphWith(s, 0, "November")
        val rightBefore = date.right
        s.replaceParagraph(date, "1 December 2026")
        assertEquals(rightBefore, paragraphWith(s, 0, "December").right, 1.0f)
        publish("aligned", s.currentFile, pages = 2, mustContain = mapOf(0 to listOf("Report", "1 December 2026")),
            mustNotContain = mapOf(0 to listOf("Quarterly", "November")))
    }

    @Test
    fun longerTextWrapsWithinTheParagraphWidthAndShrinksOnlyWhenItRunsOutOfRoom() {
        val s = open("basic.pdf")
        val para = paragraphWith(s, 0, "second", 0) // "A second paragraph follows…", 2 lines, bullets right below
        assertTrue(para.text.startsWith("A second paragraph"))
        // Room below is one more line before the bullet list. Three lines fit only if scaled.
        val longText = "A second paragraph follows after a blank line. It has now been made considerably longer " +
            "than it was, so that it no longer fits into the two lines it had before and has to make room " +
            "for itself somehow."
        val result = s.replaceParagraph(para, longText)
        assertTrue(result.changed)
        assertTrue("lines: ${result.lineCount}", result.lineCount >= 3)
        val now = paragraphWith(s, 0, "considerably")
        assertEquals(longText, now.text)
        // stays inside the original column
        assertTrue("right edge ${now.right}", now.right <= para.right + 12f)
        assertEquals(para.left, now.left, 0.2f)
        if (result.fontScale < 1f) assertTrue(now.fontSize < 12f) else assertEquals(12f, now.fontSize, 0.01f)
        assertFalse(result.overflowed)
        // the bullet list below was not run over
        val bullets = s.wordBoxes(0).first { it.first == "Review" }.second
        assertTrue("paragraph bottom ${now.bottom} vs bullets top ${bullets.top}", now.bottom <= bullets.top + 0.5f)
        publish("longer", s.currentFile, pages = 2, mustContain = mapOf(0 to listOf(longText, "Review the budget")))
    }

    @Test
    fun textThatCannotFitIsReportedAsOverflowing() {
        val s = open("basic.pdf")
        val para = paragraphWith(s, 0, "second", 0)
        val huge = (1..60).joinToString(" ") { "Sentence number $it keeps going." }
        val result = s.replaceParagraph(para, huge)
        assertTrue(result.overflowed)
        assertEquals(0.6f, result.fontScale, 0.001f)
        // nothing was lost: the whole text is in the document
        assertTrue(s.pageTextContent(0).replace("\n", " ").contains("Sentence number 60 keeps going."))
    }

    @Test
    fun aParagraphCanBeDeletedByEmptyingIt() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val before = otherWords(s, 0, "The first paragraph")
        val para = paragraphWith(s, 0, "Sales")
        val result = s.replaceParagraph(para, "   ")
        assertTrue(result.changed)
        assertEquals(0, result.lineCount)
        assertSameWords(before, s.wordBoxes(0))
        assertNull(s.paragraphAt(0, PdfPoint(200f, 160f)))
        publish("deleted", s.currentFile, pages = 2, mustContain = mapOf(0 to listOf("A second paragraph follows")),
            mustNotContain = mapOf(0 to listOf("revenue", "The first paragraph")), untouchedPrefixOf = original,
            changedAreas = areasOf(para), unchangedPages = listOf(1))
    }

    @Test
    fun manualLineBreaksAreKept() {
        val s = open("basic.pdf")
        val result = s.replaceParagraph(paragraphWith(s, 0, "Sales"), "First line\nSecond line\nThird line")
        assertEquals(3, result.lineCount)
        val words = s.wordBoxes(0)
        val first = words.first { it.first == "First" }.second
        val second = words.first { it.first == "Second" }.second
        val third = words.first { it.first == "Third" }.second
        assertEquals(15f, second.top - first.top, 0.1f) // original leading
        assertEquals(15f, third.top - second.top, 0.1f)
        assertEquals(first.left, second.left, 0.1f)
    }

    @Test
    fun justifiedParagraphsAreJustifiedAgain() {
        val original = fixture("justified.pdf")
        val s = open(original)
        val para = paragraphWith(s, 0, "stretches")
        assertEquals("Liberation Serif", para.replacementFontName)
        val before = otherWords(s, 0, "Justified text stretches")
        val newText = "This justified paragraph has been edited. The replacement is long enough to need at least " +
            "three lines at this column width, so that the lines before the last one can be stretched to the " +
            "right margin exactly like the original paragraph was."
        val result = s.replaceParagraph(para, newText)
        assertTrue(result.lineCount >= 3)
        val now = paragraphWith(s, 0, "edited.")
        assertEquals(newText, now.text)
        val lines = now.quads
        // every line but the last ends at the right margin (522) – that is what justified means
        for (i in 0 until lines.size - 1) assertEquals("line $i", 522f, lines[i].right, 1.5f)
        assertTrue(lines.last().right < 500f)
        // first line keeps its indent, the others start at the margin
        assertEquals(108f, lines[0].left, 0.5f)
        for (i in 1 until lines.size) assertEquals(90f, lines[i].left, 0.5f)
        assertSameWords(before, otherWords(s, 0, "This justified paragraph"))
        publish("justified", s.currentFile, pages = 1,
            mustContain = mapOf(0 to listOf(newText, "A Justified Chapter", "Paragraphs in this fixture", "A short final paragraph")),
            mustNotContain = mapOf(0 to listOf("stretches the spaces")), untouchedPrefixOf = original, changedAreas = areasOf(para, now))
    }

    @Test
    fun editingOneColumnDoesNotDisturbTheOther() {
        val original = fixture("columns.pdf")
        val s = open(original)
        val right = otherWords(s, 0, "The left column")
        val old = paragraphWith(s, 0, "apples,")
        s.replaceParagraph(old, "The left column now talks about oranges and lemons, which grow in warm places and are picked in winter.")
        assertSameWords(right, otherWords(s, 0, "The left column now"))
        val now = paragraphWith(s, 0, "oranges")
        assertTrue("stays in the left column: ${now.right}", now.right < 300f)
        publish("columns", s.currentFile, pages = 1,
            mustContain = mapOf(0 to listOf("oranges and lemons", "The right column is written second", "ferries, tugs and barges")),
            mustNotContain = mapOf(0 to listOf("apples")),
            untouchedPrefixOf = original, changedAreas = areasOf(old, now))
    }

    @Test
    fun textInsideASharedFormIsEditedOnThisPageOnly() {
        val original = fixture("form_shared.pdf")
        val s = open(original)
        // the line below the form's bounding box is in the file but invisible, so it is not offered
        assertFalse(s.pageTextContent(0).contains("Hidden line"))
        val page1Before = s.wordBoxes(1)
        val others = otherWords(s, 0, "Text inside a nested")
        val para = paragraphWith(s, 0, "nested")
        assertEquals("Text inside a nested form object that is drawn on more than one page.", para.text)
        s.replaceParagraph(para, "Edited on the first page only.")

        assertTrue(s.paragraphTexts(0).contains("Edited on the first page only."))
        assertFalse(s.pageTextContent(0).contains("nested form"))
        assertSameWords(others, otherWords(s, 0, "Edited on the first"))
        // page 2 draws the same form objects and must still show the original text
        assertSameWords(page1Before, s.wordBoxes(1))
        assertTrue(s.pageTextContent(1).contains("Text inside a nested form object that is drawn on more than one page."))

        val again = reopen(s.currentFile)
        assertTrue(again.paragraphTexts(0).contains("Edited on the first page only."))
        assertTrue(again.pageTextContent(1).contains("nested form object"))
        publish("form", s.currentFile, pages = 2,
            mustContain = mapOf(0 to listOf("Edited on the first page only.", "Outer form text stays untouched.", "Direct page text on page 1."),
                1 to listOf("Text inside a nested form object that is", "drawn on more than one page.", "Outer form text stays untouched.")),
            mustNotContain = mapOf(0 to listOf("nested form object")), untouchedPrefixOf = original,
            changedAreas = areasOf(para, paragraphWith(s, 0, "Edited")), unchangedPages = listOf(1))
    }

    @Test
    fun partOfATextOperatorCanBeRemovedWithoutMovingTheRest() {
        val s = open("operators.pdf")
        // "Name:" and "Alice" are drawn by a single TJ operator.
        val alice = s.wordBoxes(0).first { it.first == "Alice" }.second
        s.replaceParagraph(paragraphWith(s, 0, "Name:"), "Surname:")
        val after = s.wordBoxes(0).first { it.first == "Alice" }.second
        assertEquals(alice.left, after.left, 0.05f)
        assertEquals(alice.top, after.top, 0.05f)
        assertTrue(s.paragraphTexts(0).contains("Surname:"))
        assertFalse(s.paragraphTexts(0).contains("Name:"))

        // and the other way round: keep the start of the operator, replace the end
        s.replaceParagraph(paragraphWith(s, 0, "Alice"), "Bob")
        val surname = s.wordBoxes(0).first { it.first == "Surname:" }.second
        assertEquals(72f, surname.left, 0.1f)
        assertEquals(alice.left, s.wordBoxes(0).first { it.first == "Bob" }.second.left, 0.1f)
        publish("partial-operator", s.currentFile, pages = 4, mustContain = mapOf(0 to listOf("Surname:", "Bob", "Fake bold heading")),
            mustNotContain = mapOf(0 to listOf("Name:", "Alice")))
    }

    @Test
    fun everyKindOfTextOperatorCanBeEdited() {
        val original = fixture("operators.pdf")
        val s = open(original)
        fun edit(word: String, replacement: String) {
            val para = paragraphWith(s, 0, word)
            val untouched = s.wordBoxes(0).filter { (_, q) -> para.quads.none { it.contains((q.left + q.right) / 2, (q.top + q.bottom) / 2) } }
            s.replaceParagraph(para, replacement)
            assertTrue("'$replacement' present", pageHas(s, 0, replacement))
            // every word that was not part of the edited paragraph is still exactly in place
            val now = s.wordBoxes(0)
            for ((w, q) in untouched) {
                assertTrue("'$w' at $q is gone or moved after editing '$word'", now.any { (w2, q2) ->
                    w2 == w && abs(q2.left - q.left) < 0.05f && abs(q2.top - q.top) < 0.05f && abs(q2.right - q.right) < 0.05f })
            }
        }
        edit("Quote", "Edited the Tj line")                 // Tj
        edit("moves", "Edited the quote line")              // '
        edit("spacing", "Edited the double quote and array line") // " followed by TJ on the same line
        edit("Stretched", "Edited the scaled line")         // Tz
        edit("Energy", "Edited the superscript line")       // Ts and a font size change mid-line
        edit("Fake", "Edited the fake bold line")           // drawn twice
        val red = paragraphWith(s, 0, "Red")
        assertEquals("Liberation Sans Bold", red.replacementFontName)
        s.replaceParagraph(red, "Edited red text")
        val again = reopen(s.currentFile)
        for (t in listOf("Edited the Tj line", "Edited the quote line", "Edited the double quote and array line",
            "Edited the scaled line", "Edited the superscript line", "Edited the fake bold line", "Edited red text")) {
            assertTrue(t, pageHas(again, 0, t))
        }
        assertEquals(1, Regex("Edited the fake bold line").findAll(again.pageTextContent(0)).count())
        publish("operators", s.currentFile, pages = 4,
            mustContain = mapOf(0 to listOf("Edited the Tj line", "Edited the quote line", "Edited the double quote and array line",
                "Edited the scaled line", "Edited the superscript line", "Edited the fake bold line", "Edited red text", "Alice")),
            mustNotContain = mapOf(0 to listOf("Quote operator", "Kerned", "Stretched", "Energy", "Fake bold heading", "Red bold")),
            untouchedPrefixOf = original)
    }

    @Test
    fun pagesWithALeftoverCoordinateTransformGetUprightTextInTheRightPlace() {
        val original = fixture("operators.pdf")
        val s = open(original)
        // page 2 flips the y axis at the top level and never restores it; it also leaves a `q` open
        val para = paragraphWith(s, 1, "Flipped")
        val others = otherWords(s, 1, "Flipped coordinate")
        s.replaceParagraph(para, "This text replaced the flipped paragraph and must be upright.")
        val now = paragraphWith(s, 1, "upright.")
        assertSameAnchor("flipped page", para, now)
        assertSameWords(others, otherWords(s, 1, "This text replaced"))

        // page 3: text object split over two content streams
        s.replaceParagraph(paragraphWith(s, 2, "split"), "Joined again.")
        assertTrue(s.paragraphTexts(2).contains("Joined again."))
        assertTrue(s.paragraphTexts(2).contains("Another paragraph further down the page."))

        // page 4: font size 1 scaled by the text matrix, and text scaled by the CTM
        s.replaceParagraph(paragraphWith(s, 3, "Font"), "Still twelve points.")
        assertEquals(12f, paragraphWith(s, 3, "Still").fontSize, 0.05f)
        s.replaceParagraph(paragraphWith(s, 3, "Scaled"), "Also twelve points.")
        assertEquals(12f, paragraphWith(s, 3, "Also").fontSize, 0.05f)

        val again = reopen(s.currentFile)
        assertEquals("This text replaced the flipped paragraph and must be upright.", again.paragraphTexts(1)[0])
        publish("transforms", s.currentFile, pages = 4,
            mustContain = mapOf(1 to listOf("This text replaced the flipped paragraph and must be upright.", "Second block inside unbalanced save"),
                2 to listOf("Joined again.", "Another paragraph further"), 3 to listOf("Still twelve points.", "Also twelve points.")),
            mustNotContain = mapOf(1 to listOf("Flipped coordinate"), 2 to listOf("Content split")), untouchedPrefixOf = original,
            changedAreas = areasOf(para, now), unchangedPages = listOf(0))
    }

    @Test
    fun paragraphsOnRotatedPagesKeepTheirPlaceAndDirection() {
        val original = fixture("rotated.pdf")
        val s = open(original)
        val contain = HashMap<Int, List<String>>()
        val areas = ArrayList<String>()
        for (page in 0 until s.pageCount) {
            val word = if (page == 5) "Offset" else "Rotated"
            val para = paragraphWith(s, page, word)
            val marker = if (page == 5) emptyList() else otherWords(s, page, para.text.take(10))
            val text = "Edited text on page ${page + 1} with a second line that follows it."
            s.replaceParagraph(para, text)
            val now = paragraphWith(s, page, "Edited")
            assertEquals("page $page", text, now.text)
            // same corner, same orientation
            assertSameAnchor("page $page", para, now)
            val dirBefore = Pair(para.quads[0].x2 - para.quads[0].x1 > 1f, para.quads[0].y2 - para.quads[0].y1 > 1f)
            val dirAfter = Pair(now.quads[0].x2 - now.quads[0].x1 > 1f, now.quads[0].y2 - now.quads[0].y1 > 1f)
            assertEquals("page $page direction", dirBefore, dirAfter)
            if (page != 5) assertSameWords(marker, otherWords(s, page, "Edited text"))
            contain[page] = listOf(text)
            areas.addAll(areasOf(para, now))
        }
        val again = reopen(s.currentFile)
        for (page in 0 until again.pageCount) assertTrue(again.pageTextContent(page).contains("Edited text on page ${page + 1}"))
        publish("rotated", s.currentFile, pages = 7, mustContain = contain, untouchedPrefixOf = original, changedAreas = areas)
    }

    @Test
    fun charactersTheBuiltInFontsLackAreRefusedBeforeAnythingIsChanged() {
        val original = fixture("basic.pdf")
        val s = open(original)
        val para = paragraphWith(s, 0, "Sales")
        assertEquals("", s.unsupportedCharacters(para, "Plain text with café, € and ✓ ticks."))
        assertEquals("中文", s.unsupportedCharacters(para, "Chinese 中文 text 中"))
        try {
            s.replaceParagraph(para, "Chinese 中文 text")
            fail("must be refused")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.UNSUPPORTED_CHARACTERS, e.reason)
        }
        assertEquals("nothing was written", original, s.currentFile)
        // the paragraph object is still valid and can be used
        assertTrue(s.replaceParagraph(para, "Now a valid replacement.").changed)
    }

    @Test
    fun accentsSymbolsAndOtherAlphabetsAreWritten() {
        val s = open("basic.pdf")
        val text = "Café über Łódź costs €5 – “quoted” ✓ done → Привет αβγ"
        s.replaceParagraph(paragraphWith(s, 0, "Sales"), text)
        assertTrue(s.paragraphTexts(0).toString(), s.paragraphTexts(0).contains(text))
        val again = reopen(s.currentFile)
        assertTrue(again.paragraphTexts(0).contains(text))
        publish("unicode", s.currentFile, pages = 2, mustContain = mapOf(0 to listOf(text)))
    }

    @Test
    fun paragraphsThatCannotBeEditedAreRefused() {
        val s = open("operators.pdf")
        val invisible = paragraphWith(s, 0, "Invisible")
        try {
            s.replaceParagraph(invisible, "new")
            fail("must be refused")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.NOT_EDITABLE, e.reason)
        }
    }

    @Test
    fun aParagraphObtainedBeforeAnEditOfThatPageIsStale() {
        val s = open("basic.pdf")
        val first = paragraphWith(s, 0, "Sales")
        val second = paragraphWith(s, 0, "forecasting.")
        s.replaceParagraph(first, "Changed.")
        try {
            s.replaceParagraph(second, "Also changed.")
            fail("stale paragraph must be refused")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.STALE, e.reason)
        }
        // asking again works
        assertTrue(s.replaceParagraph(paragraphWith(s, 0, "forecasting."), "Also changed.").changed)
    }

    @Test
    fun documentsFromOtherProducersCanBeEdited() {
        val cases = listOf(
            Triple("lo_letter.pdf", "chairperson", "Liberation Serif"),
            Triple("chrome_article.pdf", "chairperson", "Liberation Serif"),
            Triple("cairo.pdf", "chairperson", "Liberation Serif"),
        )
        for ((name, word, font) in cases) {
            if (!fixtureExists(name)) continue
            val original = fixture(name)
            val s = open(original)
            val para = paragraphWith(s, 0, word)
            assertEquals(name, font, para.replacementFontName)
            val others = otherWords(s, 0, "The annual meeting")
            val text = "The annual meeting was short this year. Only the essential items were discussed, and " +
                "everybody went home early because of the weather."
            val result = s.replaceParagraph(para, text)
            assertTrue(name, result.changed)
            assertEquals(name, text, paragraphWith(s, 0, "weather.").text)
            assertSameWords(others, otherWords(s, 0, "The annual meeting was short"))
            // heading too (a different font: sans serif, bold)
            val heading = paragraphWith(s, 0, "Annual")
            assertTrue("$name heading font ${heading.replacementFontName}", heading.replacementFontName.startsWith("Liberation Sans"))
            s.replaceParagraph(heading, "Special Members Meeting")
            val areas = areasOf(para, paragraphWith(s, 0, "weather."), heading, paragraphWith(s, 0, "Special"))
            val again = reopen(s.currentFile)
            assertTrue(name, again.paragraphTexts(0).contains(text))
            assertTrue(name, again.paragraphTexts(0).contains("Special Members Meeting"))
            assertTrue(name, again.paragraphTexts(1)[0].startsWith("The second page starts with this paragraph"))
            publish(name.removeSuffix(".pdf"), s.currentFile, pages = 2,
                mustContain = mapOf(0 to listOf(text, "Special Members Meeting", "After the break the discussion turned",
                    "the résumé of the treasurer"), 1 to listOf("The second page starts with this paragraph")),
                mustNotContain = mapOf(0 to listOf("chairperson", "Annual Members")), untouchedPrefixOf = original,
                changedAreas = areas, unchangedPages = listOf(1))
        }
    }

    @Test
    fun embeddedUnicodeAndType3FontsCanBeEdited() {
        run {
            val original = fixture("fpdf_unicode.pdf")
            val s = open(original)
            val para = paragraphWith(s, 0, "embedded")
            val text = "Replaced: élève and über are still fine after editing."
            s.replaceParagraph(para, text)
            assertTrue(s.paragraphTexts(0).contains(text))
            assertTrue(s.pageTextContent(0).contains("привет мир"))
            publish("fpdf", s.currentFile, pages = 1, mustContain = mapOf(0 to listOf(text, "привет мир")),
                mustNotContain = mapOf(0 to listOf("TrueType")), untouchedPrefixOf = original,
                changedAreas = areasOf(para, paragraphWith(s, 0, "Replaced:")))
        }
        run {
            val original = fixture("matplotlib_type3.pdf")
            val s = open(original)
            val caption = paragraphWith(s, 0, "caption")
            s.replaceParagraph(caption, "The caption was edited.")
            assertTrue(s.pageTextContent(0).contains("The caption was edited."))
            assertTrue(s.pageTextContent(0).contains("Chart title in a Type 3 font"))
            publish("type3", s.currentFile, pages = 1, mustContain = mapOf(0 to listOf("The caption was edited.", "Chart title in a Type 3 font")),
                mustNotContain = mapOf(0 to listOf("glyph procedures")), untouchedPrefixOf = original,
                changedAreas = areasOf(caption, paragraphWith(s, 0, "edited.")))
        }
    }

    @Test
    fun everyFileStructureCanBeEditedAndSaved() {
        for (name in listOf("xref_stream.pdf", "linearized.pdf", "incremental.pdf", "annotated.pdf")) {
            val original = fixture(name)
            val s = open(original)
            s.replaceParagraph(paragraphWith(s, 0, "Sales"), "Edited in a $name file.")
            s.addHighlight(selectWords(s, 0, "second", "blank"))
            assertTrue(name, sameBytesPrefix(original, s.currentFile))
            val again = reopen(s.currentFile)
            assertTrue(name, again.paragraphTexts(0).contains("Edited in a $name file."))
            assertTrue(name, again.markups(0).any { it.kind == MarkupKind.HIGHLIGHT })
            assertEquals(name, 2, again.pageCount)
            // and once more on top of our own update
            again.replaceParagraph(paragraphWith(again, 1, "single"), "Second round.")
            val third = reopen(again.currentFile)
            assertTrue(name, third.paragraphTexts(1).contains("Second round."))
            assertTrue(name, third.paragraphTexts(0).contains("Edited in a $name file."))
            publish(name.removeSuffix(".pdf"), again.currentFile, pages = 2,
                mustContain = mapOf(0 to listOf("Edited in a $name file.", "Quarterly Report"), 1 to listOf("Second round.")),
                mustNotContain = mapOf(0 to listOf("revenue"), 1 to listOf("single line")))
        }
    }

    @Test
    fun aFailedVerificationLeavesTheDocumentAsItWas() {
        val original = fixture("basic.pdf")
        var reject = false
        val verifier = SaveVerifier { candidate, previous, pages, changes ->
            if (reject) throw IllegalStateException("simulated failure")
            reloadVerifier.verify(candidate, previous, pages, changes)
        }
        val s = open(original, verifier)
        s.replaceParagraph(paragraphWith(s, 0, "Quarterly"), "Annual Report")
        val good = s.currentFile.readBytes()
        val textBefore = s.paragraphTexts(0)

        reject = true
        try {
            s.replaceParagraph(paragraphWith(s, 0, "Sales"), "This must not survive.")
            fail("the edit must fail")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.VERIFICATION_FAILED, e.reason)
        }
        assertTrue(good.contentEquals(s.currentFile.readBytes()))
        assertEquals(textBefore, s.paragraphTexts(0))

        reject = false
        s.replaceParagraph(paragraphWith(s, 0, "Sales"), "This one is kept.")
        val again = reopen(s.currentFile)
        assertTrue(again.paragraphTexts(0).contains("Annual Report"))
        assertTrue(again.paragraphTexts(0).contains("This one is kept."))
        assertFalse(again.pageTextContent(0).contains("must not survive"))
        publish("after-rollback", s.currentFile, pages = 2, mustContain = mapOf(0 to listOf("Annual Report", "This one is kept.")),
            mustNotContain = mapOf(0 to listOf("must not survive", "Quarterly")), untouchedPrefixOf = original)
    }

    @Test
    fun editingAPageDeepInsideALargeDocumentIsQuick() {
        val original = fixture("big.pdf")
        val s = open(original)
        val start = System.nanoTime()
        s.replaceParagraph(paragraphWith(s, 249, "Section"), "Chapter 250")
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(s.paragraphTexts(249).contains("Chapter 250"))
        assertTrue("edit took $millis ms", millis < 5000)
        // only the changed page was appended: the update is tiny compared with the document
        assertTrue(s.currentFile.length() - original.length() < 40_000)
        assertNotNull(reopen(s.currentFile).paragraphAt(249, pointOf(s, 249, "Chapter")))
    }
}
