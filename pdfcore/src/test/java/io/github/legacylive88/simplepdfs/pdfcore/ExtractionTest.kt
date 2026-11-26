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

/** Opening documents and turning page content into lines and paragraphs. */
@RunWith(RobolectricTestRunner::class)
class ExtractionTest : PdfTestBase() {

    @Test
    fun basicDocumentIsSegmentedIntoItsParagraphs() {
        val s = open("basic.pdf")
        assertEquals(2, s.pageCount)
        assertEquals(PageSize(612f, 792f), s.pageSize(0))
        assertEquals(
            listOf(
                "Quarterly Report",
                "26 November 2026",
                "The first paragraph of this document talks about revenue for the third quarter. Sales grew " +
                    "steadily in every region, and the team expects the trend to continue into the next financial " +
                    "year without any major surprises.",
                "A second paragraph follows after a blank line. It is shorter than the first one and mentions " +
                    "the well-known risks of forecasting.",
                "• Review the budget",
                "• Hire two engineers",
                "• Publish the roadmap",
                "Page 1 of 2",
            ),
            s.paragraphTexts(0),
        )
        assertEquals(listOf("The second page has a single line of text.", "Page 2 of 2"), s.paragraphTexts(1))
        assertTrue(s.hasText(0))
    }

    @Test
    fun paragraphsSeparatedOnlyByIndentAreFound() {
        val s = open("justified.pdf")
        val p = s.paragraphTexts(0)
        assertEquals(4, p.size)
        assertEquals("A Justified Chapter", p[0])
        assertTrue(p[1].startsWith("Justified text stretches the spaces"))
        assertTrue(p[1].endsWith("at the chosen column width."))
        assertTrue(p[2].startsWith("Paragraphs in this fixture are separated only by a first line indent"))
        assertTrue(p[2].endsWith("to find the boundary."))
        assertEquals("A short final paragraph closes the page.", p[3])
    }

    @Test
    fun columnsAreNotMixed() {
        val s = open("columns.pdf")
        val p = s.paragraphTexts(0)
        assertEquals(4, p.size)
        assertTrue(p[0].startsWith("The left column is written first."))
        assertTrue(p[0].endsWith("through the winter months."))
        assertEquals("A second left paragraph mentions pears, plums and cherries as well.", p[1])
        assertTrue(p[2].startsWith("The right column is written second."))
        assertEquals("A second right paragraph mentions ferries, tugs and barges too.", p[3])
        for (text in p.subList(0, 2)) assertFalse(text, text.contains("boats") || text.contains("ferries"))
    }

    @Test
    fun unusualTextOperatorsAreUnderstood() {
        val s = open("operators.pdf")
        val p = s.paragraphTexts(0)
        assertTrue(p.contains("Quote operator first line"))
        assertTrue(p.contains("moves to the next line itself"))
        // superscript stays in its line
        assertTrue(p.contains("Energy is mc2 in this line."))
        // fake bold (same text drawn twice) is reported once
        assertEquals(1, p.count { it == "Fake bold heading" })
        // horizontal scaling
        assertTrue(p.contains("Stretched wide text"))
        // a tab-sized gap inside one operator separates two fragments
        assertTrue(p.contains("Name:"))
        assertTrue(p.contains("Alice"))
        // text drawn through a flipped coordinate system that is never restored
        assertEquals(
            listOf("Flipped coordinate system line one flipped line two continues here.", "Second block inside unbalanced save"),
            s.paragraphTexts(1),
        )
        // a text object split across two content streams
        assertTrue(s.paragraphTexts(2).contains("Another paragraph further down the page."))
        // font size 1 scaled by the text matrix, and text scaled by the CTM, both come out as 12pt
        val info = paragraphWith(s, 3, "Font")
        assertEquals(12f, info.fontSize, 0.05f)
        assertEquals(12f, paragraphWith(s, 3, "Scaled").fontSize, 0.05f)
    }

    @Test
    fun invisibleTextIsSelectableButNotEditable() {
        val s = open("operators.pdf")
        val info = paragraphWith(s, 0, "Invisible")
        assertFalse(info.editable)
        assertEquals(NotEditableReason.INVISIBLE_TEXT, info.notEditableReason)
        assertEquals("Invisible OCR text layer", selectWords(s, 0, "Invisible", "layer").text)
    }

    @Test
    fun scannedPagesHaveNoTextOrOnlyAnOcrLayer() {
        val s = open("scan.pdf")
        assertFalse(s.hasText(0))
        assertNull(s.select(0, PdfPoint(100f, 100f), PdfPoint(200f, 200f)))
        assertNull(s.paragraphAt(0, PdfPoint(100f, 100f)))
        assertTrue(s.hasText(1))
        assertEquals(NotEditableReason.INVISIBLE_TEXT, paragraphWith(s, 1, "Scanned").notEditableReason)
    }

    @Test
    fun pageRotationAndBoxesGiveTheDisplayedSize() {
        val s = open("rotated.pdf")
        assertEquals(PageSize(612f, 792f), s.pageSize(0))
        assertEquals(PageSize(792f, 612f), s.pageSize(1))
        assertEquals(PageSize(612f, 792f), s.pageSize(2))
        assertEquals(PageSize(792f, 612f), s.pageSize(3))
        assertEquals(PageSize(700f, 464f), s.pageSize(4)) // crop box 464 x 700, rotated
        assertEquals(PageSize(612f, 792f), s.pageSize(5)) // media box not at the origin
        assertEquals(PageSize(792f, 612f), s.pageSize(6)) // /Rotate -90
        for (page in 0 until s.pageCount) {
            val size = s.pageSize(page)
            val words = s.wordBoxes(page)
            assertTrue("page $page has words", words.isNotEmpty())
            for ((word, q) in words) {
                assertTrue("'$word' on page $page inside the page: $q",
                    q.left >= -0.5f && q.top >= -0.5f && q.right <= size.width + 0.5f && q.bottom <= size.height + 0.5f)
            }
        }
        // Unrotated: first line near the top left, text running left to right.
        val q0 = s.wordBoxes(0).first { it.first == "Rotated" }.second
        assertEquals(72f, q0.left, 0.5f)
        assertTrue(q0.top > 80f && q0.bottom < 96f)
        // Rotated 90 degrees clockwise: the same word is now near the top right, running downwards.
        val q1 = s.wordBoxes(1).first { it.first == "Rotated" }.second
        assertEquals(72f, q1.top, 0.5f)
        assertTrue("$q1", q1.left > 792f - 96f && q1.right < 792f - 80f)
        // Upside down.
        val q2 = s.wordBoxes(2).first { it.first == "Rotated" }.second
        assertEquals(612f - 72f, q2.right, 0.5f)
        assertTrue("$q2", q2.bottom < 792f - 80f && q2.top > 792f - 96f)
        // Rotated 270: near the bottom left, running upwards.
        val q3 = s.wordBoxes(3).first { it.first == "Rotated" }.second
        assertEquals(612f - 72f, q3.bottom, 0.5f)
        assertTrue("$q3", q3.left > 80f && q3.right < 96f)
        // /Rotate -90 is the same as 270.
        assertEquals(q3, s.wordBoxes(6).first { it.first == "Rotated" }.second)
        // The offset media box does not shift text.
        val q5 = s.wordBoxes(5).first { it.first == "Offset" }.second
        assertEquals(72f, q5.left, 0.5f)
        assertTrue(q5.top > 80f && q5.bottom < 96f)
    }

    @Test
    fun textFromOtherProducersIsRead() {
        for (name in listOf("lo_letter.pdf", "chrome_article.pdf", "cairo.pdf")) {
            if (!fixtureExists(name)) continue
            val s = open(name)
            val p = s.paragraphTexts(0)
            assertEquals(name, "Annual Members Meeting", p[0])
            assertTrue(name, p[1].startsWith("The annual meeting of members was held in the main hall"))
            assertTrue(name, p[1].endsWith("which were adopted without changes."))
            assertTrue(name, p[2].startsWith("After the break the discussion turned to the building project."))
            assertTrue(name, p.contains("Café prices, naïve questions and the résumé of the treasurer were also discussed."))
            assertTrue(name, s.paragraphTexts(1)[0].startsWith("The second page starts with this paragraph"))
            assertTrue(name, paragraphWith(s, 0, "annual").editable)
        }
    }

    @Test
    fun unicodeFromEmbeddedTrueTypeFontsIsRead() {
        val s = open("fpdf_unicode.pdf")
        val all = s.pageTextContent(0)
        assertTrue(all, all.contains("élève, über, Łódź and ñandú"))
        assertTrue(all, all.contains("привет мир"))
        assertTrue(all, all.contains("αβγδ"))
    }

    @Test
    fun type3FontTextIsRead() {
        val s = open("matplotlib_type3.pdf")
        val all = s.pageTextContent(0)
        assertTrue(all, all.contains("Chart title in a Type 3 font"))
        assertTrue(all, all.contains("A caption line drawn with glyph procedures."))
    }

    @Test
    fun structuralVariantsOpen() {
        for (name in listOf("xref_stream.pdf", "linearized.pdf", "incremental.pdf", "annotated.pdf")) {
            val s = open(name)
            assertEquals(name, 2, s.pageCount)
            assertEquals(name, "Quarterly Report", s.paragraphTexts(0)[0])
            assertFalse(name, s.capabilities.encrypted)
            assertTrue(name, s.capabilities.canEditContent && s.capabilities.canAnnotate && s.capabilities.canCopyText)
        }
    }

    @Test
    fun encryptedDocumentsAreViewOnly() {
        val s = open("encrypted_owner.pdf")
        assertTrue(s.capabilities.encrypted)
        assertFalse(s.capabilities.canAnnotate)
        assertFalse(s.capabilities.canEditContent)
        assertFalse("the author disallowed copying", s.capabilities.canCopyText)
        // geometry still works so a selection can be shown, but no text is handed out
        val sel = selectWords(s, 0, "Sales", "trend")
        assertEquals("", sel.text)
        assertTrue(sel.quads.isNotEmpty())
        assertEquals(NotEditableReason.DOCUMENT_PROTECTED, paragraphWith(s, 0, "Sales").notEditableReason)
        try {
            s.addHighlight(sel)
            fail("highlighting a protected document must be refused")
        } catch (e: PdfEditException) {
            assertEquals(PdfEditException.Reason.NOT_ALLOWED, e.reason)
        }
    }

    @Test
    fun passwordProtectedDocumentAsksForPassword() {
        val file = fixture("encrypted_user.pdf")
        try {
            PdfSession.open(context, file, File(file.parentFile, "work"))
            fail("expected a password request")
        } catch (e: PdfPasswordRequiredException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun somethingThatIsNotAPdfIsRejectedCleanly() {
        val file = File(tmp.newFolder(), "not.pdf")
        file.writeText("This is just a text file, not a PDF at all.\n".repeat(20))
        try {
            PdfSession.open(context, file, File(file.parentFile, "work"))
            fail("expected a failure")
        } catch (e: PdfPasswordRequiredException) {
            fail("wrong kind of failure")
        } catch (e: PdfOpenException) {
            // expected
        }
        val empty = File(tmp.newFolder(), "empty.pdf")
        empty.writeBytes(ByteArray(0))
        try {
            PdfSession.open(context, empty, File(empty.parentFile, "work"))
            fail("expected a failure")
        } catch (e: PdfOpenException) {
            // expected
        }
    }

    @Test
    fun pagesWithFormFieldsStampsOrNotesAreReported() {
        // page 1: a filled-in form field, a stamp and a note; page 2: only a link and a hidden stamp
        val s = open("form_stamp.pdf")
        assertEquals(listOf(0), s.pagesWithOtherAnnotations())
        // the annotations do not disturb the text tools
        assertTrue(s.pageTextContent(0).startsWith("Quarterly Report"))
        // text markup is drawn by the app itself, so it does not count
        assertEquals(emptyList<Int>(), open("annotated.pdf").pagesWithOtherAnnotations())
        assertEquals(emptyList<Int>(), open("basic.pdf").pagesWithOtherAnnotations())
        assertEquals(emptyList<Int>(), open("big.pdf").pagesWithOtherAnnotations())
    }

    @Test
    fun aLargeDocumentOpensAndAnyPageCanBeRead() {
        val s = open("big.pdf")
        assertEquals(300, s.pageCount)
        assertTrue(s.paragraphTexts(249)[0].startsWith("Section 250"))
        assertTrue(s.pageTextContent(299).contains("Line 30 of page 300"))
    }
}
