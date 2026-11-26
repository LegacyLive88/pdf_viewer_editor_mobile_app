package io.github.legacylive88.simplepdfs.pdfcore.internal

/**
 * Greedy word wrapping for replacement text. Pure logic with no PDF dependencies: widths come
 * from the [measure] callback (width of one code point at font size 1).
 */
internal object TextLayout {

    class Line(
        /** The words on this line; joined by single spaces when drawn. */
        @JvmField val words: List<String>,
        /** Natural width at font size 1 (words plus single spaces). */
        @JvmField val width: Float,
        /** True for the last line of a paragraph (or before a manual line break): never justified. */
        @JvmField val last: Boolean,
    ) {
        val text: String get() = words.joinToString(" ")
        val spaces: Int get() = (words.size - 1).coerceAtLeast(0)
    }

    /** Makes typed text safe to typeset: tabs become spaces, other control characters go. */
    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code -> sb.append('\n')
                cp == '\r'.code -> if (i >= text.length || text[i] != '\n') sb.append('\n')
                cp == '\t'.code -> sb.append("    ")
                cp == 0x2028 || cp == 0x2029 -> sb.append('\n')
                // Typographic spaces have no glyph in most fonts; a plain space draws the same.
                cp in 0x2000..0x200A || cp == 0x202F || cp == 0x205F || cp == 0x3000 -> sb.append(' ')
                Character.isISOControl(cp) -> {}
                cp == 0xFEFF || cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 -> {}
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    fun widthOf(s: String, measure: (Int) -> Float): Float {
        var w = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            w += measure(cp)
        }
        return w
    }

    /**
     * Wraps [text] (already sanitised).
     *
     * @param firstWidth available width for the first line, at font size 1
     * @param otherWidth available width for every further line, at font size 1
     */
    fun wrap(text: String, firstWidth: Float, otherWidth: Float, measure: (Int) -> Float): List<Line> {
        val space = measure(' '.code)
        val out = ArrayList<Line>()
        val hardParagraphs = text.split('\n')
        for (para in hardParagraphs) {
            val words = para.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out.add(Line(emptyList(), 0f, true))
                continue
            }
            var current = ArrayList<String>()
            var currentWidth = 0f

            fun limit(): Float = if (out.isEmpty()) firstWidth else otherWidth
            fun emit(last: Boolean) {
                out.add(Line(current, currentWidth, last))
                current = ArrayList()
                currentWidth = 0f
            }

            for (word in words) {
                var w = widthOf(word, measure)
                var piece = word
                if (current.isNotEmpty()) {
                    if (currentWidth + space + w <= limit()) {
                        current.add(piece)
                        currentWidth += space + w
                        continue
                    }
                    emit(false)
                }
                // The word starts a line. If it is wider than the line, split it by characters.
                while (w > limit()) {
                    val cut = fitPrefix(piece, limit(), measure)
                    val head = piece.substring(0, cut)
                    current.add(head)
                    currentWidth = widthOf(head, measure)
                    emit(false)
                    piece = piece.substring(cut)
                    w = widthOf(piece, measure)
                    if (piece.isEmpty()) break
                }
                if (piece.isNotEmpty()) {
                    current.add(piece)
                    currentWidth = w
                }
            }
            if (current.isNotEmpty()) emit(true) else if (out.isNotEmpty()) {
                // The paragraph ended exactly on a forced break; mark the previous line as last.
                val prev = out.removeAt(out.size - 1)
                out.add(Line(prev.words, prev.width, true))
            }
        }
        return out
    }

    /** Longest prefix of [s] (at least one character) that fits into [limit]. */
    private fun fitPrefix(s: String, limit: Float, measure: (Int) -> Float): Int {
        var w = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val cw = measure(cp)
            if (i > 0 && w + cw > limit) return i
            w += cw
            i += Character.charCount(cp)
        }
        return s.length
    }
}
