package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.pdmodel.PDPage
import io.github.legacylive88.simplepdfs.pdfcore.NotEditableReason
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the engine knows about the text of one page at one point in time.
 * A new instance (with a new [revision]) is built whenever the page content changes.
 */
internal class PageText(
    @JvmField val pageIndex: Int,
    @JvmField val revision: Int,
    @JvmField val geom: PageGeometry,
    @JvmField val glyphs: List<Glyph>,
    @JvmField val contexts: List<StreamContext>,
    @JvmField val endState: EndState,
    @JvmField val layout: PageLayout,
) {
    val hasText: Boolean get() = layout.ordered.isNotEmpty()

    // --- hit testing ------------------------------------------------------------------------------

    /** Distance from a point to a line's box (0 inside), measured in the line's own frame. */
    private fun distanceTo(line: TextLine, x: Float, y: Float): Float {
        val u = line.frame.u(x, y)
        val v = line.frame.v(x, y)
        val du = if (u < line.u0) line.u0 - u else if (u > line.u1) u - line.u1 else 0f
        val dv = if (v < line.top) line.top - v else if (v > line.bottom) v - line.bottom else 0f
        return kotlin.math.sqrt(du * du + dv * dv)
    }

    /**
     * How strongly a tap "means" a line. People think in rows: a tap beside a line, even far
     * out in the margin, refers to that line rather than to a nearer line above or below.
     * Distance along the row therefore counts much less than distance across rows.
     */
    private fun tapDistance(line: TextLine, x: Float, y: Float): Float {
        val u = line.frame.u(x, y)
        val v = line.frame.v(x, y)
        val du = if (u < line.u0) line.u0 - u else if (u > line.u1) u - line.u1 else 0f
        val dv = if (v < line.top) line.top - v else if (v > line.bottom) v - line.bottom else 0f
        return dv + ALONG_ROW_WEIGHT * du
    }

    /** The line nearest to a point, or null on a page without text. */
    fun nearestLine(x: Float, y: Float): TextLine? {
        var best: TextLine? = null
        var bestDistance = Float.MAX_VALUE
        for (line in layout.lines) {
            if (line.words.isEmpty()) continue
            val d = tapDistance(line, x, y)
            // Prefer the line whose baseline is closest when boxes overlap (tight leading).
            if (d < bestDistance - 0.01f ||
                (abs(d - bestDistance) <= 0.01f && best != null &&
                    abs(line.frame.v(x, y) - line.v) < abs(best.frame.v(x, y) - best.v))
            ) {
                bestDistance = d
                best = line
            }
        }
        return best
    }

    /** The word of [line] nearest to the point. */
    fun nearestWord(line: TextLine, x: Float, y: Float): Word {
        val u = line.frame.u(x, y)
        var best = line.words[0]
        var bestDistance = Float.MAX_VALUE
        for (w in line.words) {
            val a = line.glyphs[w.first]
            val b = line.glyphs[w.last]
            val start = line.glyphU(a)
            val end = line.glyphU(b) + b.width
            val d = if (u < start) start - u else if (u > end) u - end else 0f
            if (d < bestDistance) {
                bestDistance = d
                best = w
            }
        }
        return best
    }

    // --- selection --------------------------------------------------------------------------------

    class Range(@JvmField val startSeq: Int, @JvmField val endSeq: Int)

    /**
     * The range of text between two tapped points. Each point snaps to the nearest word, and
     * whole words are selected: fingers are not precise enough to hit a single letter.
     */
    fun rangeBetween(ax: Float, ay: Float, bx: Float, by: Float): Range? {
        val la = nearestLine(ax, ay) ?: return null
        val lb = nearestLine(bx, by) ?: return null
        val wa = nearestWord(la, ax, ay)
        val wb = nearestWord(lb, bx, by)
        val aStart = la.glyphs[wa.first].seq
        val aEnd = lastSeq(la, wa)
        val bStart = lb.glyphs[wb.first].seq
        val bEnd = lastSeq(lb, wb)
        if (aStart < 0 || bStart < 0) return null
        return Range(min(aStart, bStart), max(aEnd, bEnd))
    }

    private fun lastSeq(line: TextLine, w: Word): Int {
        var i = w.last
        while (i >= w.first) {
            val s = line.glyphs[i].seq
            if (s >= 0) return s
            i--
        }
        return line.glyphs[w.first].seq
    }

    /** One box per line fragment covered by the range. */
    fun quadsFor(range: Range): List<Quad> {
        val out = ArrayList<Quad>()
        var s = range.startSeq
        while (s <= range.endSeq) {
            val line = layout.lineOf[s]
            var e = s
            while (e + 1 <= range.endSeq && layout.lineOf[e + 1] === line) e++
            val first = layout.ordered[s]
            val last = layout.ordered[e]
            var u0 = line.glyphU(first)
            var u1 = line.glyphU(last) + last.width
            if (u1 < u0) {
                val t = u0
                u0 = u1
                u1 = t
            }
            out.add(line.frame.quad(u0, u1, line.top, line.bottom))
            s = e + 1
        }
        return out
    }

    /** The text of the range, with lines of a paragraph flowing together. */
    fun textFor(range: Range): String {
        val paragraphs = ArrayList<String>()
        var lines = ArrayList<String>()
        var currentParagraph = -1
        var s = range.startSeq
        while (s <= range.endSeq) {
            val line = layout.lineOf[s]
            var e = s
            while (e + 1 <= range.endSeq && layout.lineOf[e + 1] === line) e++
            val sb = StringBuilder()
            for (w in line.words) {
                val ws = line.glyphs[w.first].seq
                if (ws < s || ws > e) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(w.text)
            }
            if (line.paragraph != currentParagraph) {
                if (lines.isNotEmpty()) paragraphs.add(LayoutAnalyzer.joinLines(lines))
                lines = ArrayList()
                currentParagraph = line.paragraph
            }
            lines.add(sb.toString())
            s = e + 1
        }
        if (lines.isNotEmpty()) paragraphs.add(LayoutAnalyzer.joinLines(lines))
        return paragraphs.filter { it.isNotEmpty() }.joinToString("\n")
    }

    // --- paragraphs -------------------------------------------------------------------------------

    /** The paragraph under a point, or null if the point is not on text. */
    fun paragraphAt(x: Float, y: Float): TextParagraph? {
        // A line that (nearly) contains the point wins.
        var best: TextLine? = null
        var bestDistance = Float.MAX_VALUE
        for (line in layout.lines) {
            if (line.paragraph < 0) continue
            val d = distanceTo(line, x, y)
            if (d <= 0.6f * line.size && d < bestDistance) {
                bestDistance = d
                best = line
            }
        }
        if (best != null) return layout.paragraphs[best.paragraph]
        // Otherwise accept the gap between two lines of the same paragraph.
        for (p in layout.paragraphs) {
            val u = p.frame.u(x, y)
            val v = p.frame.v(x, y)
            if (u >= p.left && u <= p.right && v >= p.top && v <= p.bottom) return p
        }
        return null
    }

    fun lineQuads(p: TextParagraph): List<Quad> = p.lines.map { it.frame.quad(it.u0, it.u1, it.top, it.bottom) }

    fun notEditableReason(p: TextParagraph): NotEditableReason? {
        var visible = 0
        var total = 0
        for (line in p.lines) {
            for (g in line.glyphs) {
                if (g.vertical) return NotEditableReason.UNSUPPORTED_WRITING_DIRECTION
                if (g.duplicate || g.text.isEmpty()) continue
                if (!g.mapped) return NotEditableReason.UNREADABLE_CHARACTERS
                total++
                if (g.renderMode != 3 && g.renderMode != 7) visible++
                for (ch in g.text) if (isRightToLeft(ch)) return NotEditableReason.UNSUPPORTED_WRITING_DIRECTION
            }
        }
        if (total > 0 && visible == 0) return NotEditableReason.INVISIBLE_TEXT
        return null
    }

    companion object {
        private const val ALONG_ROW_WEIGHT = 0.15f

        fun isRightToLeft(ch: Char): Boolean {
            val d = Character.getDirectionality(ch)
            return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        }

        /** A page without any usable text (blank, scanned, or with content that cannot be parsed). */
        fun empty(page: PDPage, pageIndex: Int, revision: Int): PageText = PageText(
            pageIndex, revision, PageGeometry.of(page), emptyList(), emptyList(),
            EndState(0, Affine.IDENTITY, false),
            PageLayout(emptyList(), emptyList(), emptyList(), emptyList()),
        )

        fun extract(page: PDPage, pageIndex: Int, revision: Int): PageText {
            val geom = PageGeometry.of(page)
            val extractor = GlyphExtractor(page, geom)
            extractor.run()
            val layout = LayoutAnalyzer.analyze(extractor.glyphs, geom)
            return PageText(pageIndex, revision, geom, extractor.glyphs, extractor.contexts, extractor.endState, layout)
        }
    }
}
