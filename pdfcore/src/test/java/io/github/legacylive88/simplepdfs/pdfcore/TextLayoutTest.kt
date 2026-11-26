package io.github.legacylive88.simplepdfs.pdfcore

import io.github.legacylive88.simplepdfs.pdfcore.internal.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word wrapping of replacement text (feature 6). Pure logic, no PDF involved. */
class TextLayoutTest {

    /** Every character is one unit wide, so widths can be read off the strings. */
    private val mono: (Int) -> Float = { 1f }

    private fun lines(text: String, first: Float, other: Float = first) =
        TextLayout.wrap(text, first, other, mono).map { it.text }

    @Test
    fun wordsAreWrappedAtTheAvailableWidth() {
        assertEquals(listOf("aaa bbb", "ccc ddd", "eee"), lines("aaa bbb ccc ddd eee", 7f))
        assertEquals(listOf("aaa bbb ccc"), lines("aaa bbb ccc", 11f))
        assertEquals(listOf("aaa bbb", "ccc"), lines("aaa bbb ccc", 10f))
    }

    @Test
    fun theFirstLineCanHaveADifferentWidth() {
        // indented first line: only 5 units, then 11
        assertEquals(listOf("aaa", "bbb ccc ddd", "eee"), lines("aaa bbb ccc ddd eee", 5f, 11f))
        // hanging indent: wider first line
        assertEquals(listOf("aaa bbb ccc", "ddd", "eee"), lines("aaa bbb ccc ddd eee", 11f, 5f))
    }

    @Test
    fun lineBreaksTypedByTheUserAreKept() {
        assertEquals(listOf("first", "second"), lines("first\nsecond", 50f))
        assertEquals(listOf("first", "", "third"), lines("first\n\nthird", 50f))
        val wrapped = TextLayout.wrap("aaa bbb\nccc", 3f, 3f, mono)
        assertEquals(listOf("aaa", "bbb", "ccc"), wrapped.map { it.text })
        // a line is "last" when a paragraph ends there; only the others may be justified
        assertEquals(listOf(false, true, true), wrapped.map { it.last })
    }

    @Test
    fun aWordLongerThanTheLineIsSplit() {
        assertEquals(listOf("abcd", "efgh", "ij"), lines("abcdefghij", 4f))
        // the pieces fill their lines, so the next word starts a new one
        assertEquals(listOf("xx", "abcde", "fghij", "yy"), lines("xx abcdefghij yy", 5f))
        // never an endless loop, even when nothing fits
        assertEquals(listOf("a", "b", "c"), lines("abc", 0.5f))
        val exact = TextLayout.wrap("abcdefgh", 4f, 4f, mono)
        assertEquals(listOf("abcd", "efgh"), exact.map { it.text })
        assertTrue("the paragraph still ends on its last line", exact.last().last)
    }

    @Test
    fun repeatedSpacesCollapseAndWidthsAreMeasured() {
        val l = TextLayout.wrap("aa   bb    cc", 20f, 20f, mono)
        assertEquals(listOf("aa bb cc"), l.map { it.text })
        assertEquals(8f, l[0].width, 0.001f)
        assertEquals(2, l[0].spaces)
        assertTrue(l[0].last)
        assertEquals(3.5f, TextLayout.widthOf("abc", { if (it == 'b'.code) 1.5f else 1f }), 0.001f)
    }

    @Test
    fun typedTextIsCleanedBeforeItIsTypeset() {
        assertEquals("a    b", TextLayout.sanitize("a\tb"))
        assertEquals("a\nb\nc\nd", TextLayout.sanitize("a\r\nb\rc\u2028d"))
        assertEquals("ab", TextLayout.sanitize("a\u0000\u0007\u200B\uFEFFb"))
        assertEquals("thin space", TextLayout.sanitize("thin\u2009space"))
        assertEquals("ideographic space", TextLayout.sanitize("ideographic\u3000space"))
        // a non-breaking space is a real character with a glyph and is kept
        assertEquals("10\u00A0km", TextLayout.sanitize("10\u00A0km"))
        // characters outside the basic plane survive
        assertEquals("ok 😀", TextLayout.sanitize("ok 😀"))
        assertFalse(TextLayout.sanitize("x\u0001y").contains('\u0001'))
    }
}
