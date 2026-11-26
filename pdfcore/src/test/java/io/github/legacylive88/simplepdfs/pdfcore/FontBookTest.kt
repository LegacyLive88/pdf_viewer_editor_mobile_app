package io.github.legacylive88.simplepdfs.pdfcore

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDFontFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.github.legacylive88.simplepdfs.pdfcore.internal.BundledFontMapper
import io.github.legacylive88.simplepdfs.pdfcore.internal.FontBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which bundled font stands in for a document's font when a paragraph is edited (feature 6). */
@RunWith(RobolectricTestRunner::class)
class FontBookTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var fonts: FontBook

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        BundledFontMapper.install { context.assets.open(it) }
        fonts = FontBook { context.assets.open(it) }
    }

    /** A font as a PDF would declare it without embedding it. */
    private fun named(baseFont: String): PDFont {
        val d = COSDictionary()
        d.setItem(COSName.TYPE, COSName.FONT)
        d.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE)
        d.setName(COSName.BASE_FONT, baseFont)
        return PDFontFactory.createFont(d)
    }

    @Test
    fun standardFontsMapToTheirMetricCompatibleCounterparts() {
        assertEquals(FontBook.Style(FontBook.Family.SANS, false, false), FontBook.styleFor(PDType1Font.HELVETICA))
        assertEquals(FontBook.Style(FontBook.Family.SANS, true, false), FontBook.styleFor(PDType1Font.HELVETICA_BOLD))
        assertEquals(FontBook.Style(FontBook.Family.SANS, false, true), FontBook.styleFor(PDType1Font.HELVETICA_OBLIQUE))
        assertEquals(FontBook.Style(FontBook.Family.SERIF, false, false), FontBook.styleFor(PDType1Font.TIMES_ROMAN))
        assertEquals(FontBook.Style(FontBook.Family.SERIF, true, true), FontBook.styleFor(PDType1Font.TIMES_BOLD_ITALIC))
        assertEquals(FontBook.Style(FontBook.Family.MONO, false, false), FontBook.styleFor(PDType1Font.COURIER))
        assertEquals(FontBook.Style(FontBook.Family.MONO, true, true), FontBook.styleFor(PDType1Font.COURIER_BOLD_OBLIQUE))
    }

    @Test
    fun commonFontNamesAreRecognisedIncludingSubsetPrefixes() {
        assertEquals(FontBook.Style(FontBook.Family.SANS, true, false), FontBook.styleFor(named("ABCDEF+Arial-BoldMT")))
        assertEquals(FontBook.Style(FontBook.Family.SERIF, false, true), FontBook.styleFor(named("XYZABC+TimesNewRomanPS-ItalicMT")))
        assertEquals(FontBook.Style(FontBook.Family.SANS, false, false), FontBook.styleFor(named("Calibri")))
        assertEquals(FontBook.Style(FontBook.Family.SERIF, false, false), FontBook.styleFor(named("Georgia")))
        assertEquals(FontBook.Style(FontBook.Family.MONO, false, false), FontBook.styleFor(named("Consolas")))
        assertEquals(FontBook.Style(FontBook.Family.SERIF, true, false), FontBook.styleFor(named("CMBX10")))
        assertEquals("an unknown name falls back to sans", FontBook.Family.SANS, FontBook.styleFor(named("Whatever")).family)
    }

    @Test
    fun everyStyleHasAFontFileInTheApp() {
        for (family in FontBook.Family.values()) for (bold in listOf(false, true)) for (italic in listOf(false, true)) {
            val style = FontBook.Style(family, bold, italic)
            fonts.open(style.asset).use { assertTrue(style.asset, it.read() >= 0) }
            assertTrue(style.label.startsWith("Liberation "))
            assertTrue("'A' in ${style.asset}", fonts.hasGlyph(style.asset, 'A'.code))
        }
        fonts.open(FontBook.FALLBACK_ASSET).use { assertTrue(it.read() >= 0) }
        assertEquals("Liberation Serif Bold Italic", FontBook.Style(FontBook.Family.SERIF, true, true).label)
    }

    @Test
    fun charactersNoFontCanDrawAreListedOnce() {
        val style = FontBook.Style(FontBook.Family.SANS, false, false)
        assertEquals("", fonts.unsupported(style, "Plain text, café, € 5, Привет, αβγ"))
        // the fallback font covers symbols Liberation does not have
        assertFalse(fonts.hasGlyph(style.asset, 0x2713))
        assertTrue(fonts.hasGlyph(FontBook.FALLBACK_ASSET, 0x2713))
        assertEquals("", fonts.unsupported(style, "done ✓"))
        // neither has Chinese or Korean
        assertEquals("中文", fonts.unsupported(style, "中文 and 中 again"))
        assertEquals("한", fonts.unsupported(style, "Korean 한"))
        // characters beyond the basic plane are handled as one character, not two halves
        assertEquals("", fonts.unsupported(style, "smile 😀"))
        assertEquals("🧠", fonts.unsupported(style, "brain 🧠"))
        // line breaks and spaces are never a problem
        assertEquals("", fonts.unsupported(style, "a\nb\tc  d"))
    }
}
