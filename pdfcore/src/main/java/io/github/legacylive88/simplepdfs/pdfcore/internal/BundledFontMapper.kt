package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.fontbox.FontBoxFont
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.CIDFontMapping
import com.tom_roush.pdfbox.pdmodel.font.FontMapper
import com.tom_roush.pdfbox.pdmodel.font.FontMappers
import com.tom_roush.pdfbox.pdmodel.font.FontMapping
import com.tom_roush.pdfbox.pdmodel.font.PDCIDSystemInfo
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import java.io.InputStream

/**
 * Tells PdfBox-Android which font to use for fonts a PDF refers to but does not embed.
 *
 * The library's default answer is to scan and parse every font installed on the device the
 * first time it is asked, which takes seconds on a phone. This engine never draws glyphs with
 * the library (pages are rendered by the platform), so *which* substitute is used does not
 * matter here: character widths come from the PDF itself or, for the 14 standard fonts, from
 * the metrics shipped with the library. One bundled font therefore answers every request.
 */
internal class BundledFontMapper(private val font: TrueTypeFont) : FontMapper {

    override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<TrueTypeFont> =
        FontMapping(font, true)

    override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<FontBoxFont> =
        FontMapping(font, true)

    override fun getCIDFont(
        baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?,
    ): CIDFontMapping = CIDFontMapping(null, font, true)

    companion object {
        @Volatile
        private var installed = false

        /** Installs the mapper once per process. Must run before the first document is loaded. */
        fun install(openAsset: (String) -> InputStream) {
            if (installed) return
            synchronized(this) {
                if (installed) return
                val ttf = openAsset("fonts/LiberationSans-Regular.ttf").use { TTFParser().parse(it) }
                FontMappers.set(BundledFontMapper(ttf))
                installed = true
            }
        }
    }
}
