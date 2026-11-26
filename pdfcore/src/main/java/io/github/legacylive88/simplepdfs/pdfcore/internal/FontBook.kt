package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.InputStream
import java.util.Locale

/**
 * The fonts bundled with the app for writing replacement text.
 *
 * A PDF usually embeds only the glyphs it uses (a *subset*), so the original font cannot draw
 * newly typed characters. Edited paragraphs are therefore re-typeset in an open font that is
 * embedded into the document:
 *  - **Liberation Sans / Serif / Mono** (SIL OFL 1.1) – metric-compatible with Arial/Helvetica,
 *    Times New Roman and Courier New, so text set in those very common fonts keeps its width;
 *  - **DejaVu Sans** as a fallback for characters Liberation lacks (many symbols and scripts).
 */
internal class FontBook(private val openAsset: (String) -> InputStream) {

    enum class Family(val assetPart: String, val label: String) {
        SANS("Sans", "Liberation Sans"),
        SERIF("Serif", "Liberation Serif"),
        MONO("Mono", "Liberation Mono"),
    }

    data class Style(val family: Family, val bold: Boolean, val italic: Boolean) {
        val asset: String
            get() {
                val variant = when {
                    bold && italic -> "BoldItalic"
                    bold -> "Bold"
                    italic -> "Italic"
                    else -> "Regular"
                }
                return "fonts/Liberation${family.assetPart}-$variant.ttf"
            }

        val label: String
            get() = buildString {
                append(family.label)
                if (bold) append(" Bold")
                if (italic) append(" Italic")
            }
    }

    private val coverage = HashMap<String, TrueTypeFont>()

    fun open(asset: String): InputStream = openAsset(asset)

    /** A parsed copy of the font used only to answer "is there a glyph for this character?". */
    @Synchronized
    private fun parsed(asset: String): TrueTypeFont = coverage.getOrPut(asset) {
        openAsset(asset).use { TTFParser().parse(it) }
    }

    private fun lookup(asset: String): CmapLookup = parsed(asset).getUnicodeCmapLookup(false)

    fun hasGlyph(asset: String, codePoint: Int): Boolean = try {
        lookup(asset).getGlyphId(codePoint) != 0
    } catch (_: Exception) {
        false
    }

    /**
     * Characters of [text] that none of the fonts for [style] can draw, each listed once.
     * Line breaks and other whitespace never count as missing.
     */
    fun unsupported(style: Style, text: String): String {
        val missing = LinkedHashSet<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)) continue
            if (!hasGlyph(style.asset, cp) && !hasGlyph(FALLBACK_ASSET, cp)) missing.add(cp)
        }
        val sb = StringBuilder()
        for (cp in missing) sb.appendCodePoint(cp)
        return sb.toString()
    }

    @Synchronized
    fun close() {
        for (ttf in coverage.values) try {
            ttf.close()
        } catch (_: Exception) {
        }
        coverage.clear()
    }

    companion object {
        const val FALLBACK_ASSET = "fonts/DejaVuSans.ttf"

        private val SERIF_HINTS = listOf(
            "times", "serif", "roman", "georgia", "garamond", "cambria", "minion", "palatino", "book",
            "charter", "libertine", "caslon", "baskerville", "bodoni", "century", "didot", "tinos",
            "nimbusrom", "cmr", "cmbx", "cmti", "lmroman", "utopia", "constantia", "antiqua",
        )
        private val MONO_HINTS = listOf(
            "courier", "mono", "consolas", "menlo", "typewriter", "cousine", "fixed", "terminal",
            "lucidacon", "cmtt", "inconsolata", "sourcecode", "firacode",
        )
        private val SANS_HINTS = listOf("sans", "arial", "helvet", "verdana", "tahoma", "calibri", "arimo", "roboto", "segoe")
        private val BOLD_HINTS = listOf("bold", "black", "heavy", "semibold", "demi", "extrabold", "ultra", "cmbx")
        private val ITALIC_HINTS = listOf("italic", "oblique", "slanted", "cmti", "kursiv")

        /** Picks the bundled font that best stands in for [font]. */
        fun styleFor(font: PDFont): Style {
            var name = try {
                font.name ?: ""
            } catch (_: Exception) {
                ""
            }
            // Subset fonts are prefixed "ABCDEF+".
            val plus = name.indexOf('+')
            if (plus == 6) name = name.substring(7)
            val n = name.lowercase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "")

            val fd = try {
                font.fontDescriptor
            } catch (_: Exception) {
                null
            }

            var bold = BOLD_HINTS.any { n.contains(it) }
            var italic = ITALIC_HINTS.any { n.contains(it) }
            if (fd != null) {
                try {
                    if (fd.isForceBold || fd.fontWeight >= 600f) bold = true
                    if (fd.isItalic || kotlin.math.abs(fd.italicAngle) > 4f) italic = true
                } catch (_: Exception) {
                }
            }

            // The name is the best evidence; descriptor flags are often left at defaults.
            val family = when {
                MONO_HINTS.any { n.contains(it) } -> Family.MONO
                SANS_HINTS.any { n.contains(it) } -> Family.SANS
                SERIF_HINTS.any { n.contains(it) } -> Family.SERIF
                fd != null && safe { fd.isFixedPitch } -> Family.MONO
                fd != null && safe { fd.isSerif } -> Family.SERIF
                else -> Family.SANS
            }
            return Style(family, bold, italic)
        }

        private inline fun safe(block: () -> Boolean): Boolean = try {
            block()
        } catch (_: Exception) {
            false
        }
    }
}
