package io.github.legacylive88.simplepdfs.pdfcore.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A word: a run of adjacent non-space glyphs on one line. Indices are into [TextLine.glyphs]. */
internal class Word(@JvmField val first: Int, @JvmField val last: Int, @JvmField val text: String)

/** One visual line of text (or one fragment of it, when a row is split into columns/cells). */
internal class TextLine(
    /** All glyphs of the line in reading order, including [Glyph.duplicate] ones. */
    @JvmField val glyphs: List<Glyph>,
    @JvmField val frame: Frame,
    /** Baseline angle in whole degrees (0 = upright, left to right). */
    @JvmField val bucket: Int,
) {
    @JvmField var u0 = 0f
    @JvmField var u1 = 0f
    /** Baseline position along the frame's v axis. */
    @JvmField var v = 0f
    @JvmField var top = 0f
    @JvmField var bottom = 0f
    /** Dominant font size, display units. */
    @JvmField var size = 0f
    @JvmField var firstStream = 0
    @JvmField var words: List<Word> = emptyList()
    @JvmField var text: String = ""
    @JvmField var paragraph: Int = -1

    val width: Float get() = u1 - u0
    val centre: Float get() = (u0 + u1) / 2f

    fun glyphU(g: Glyph): Float = frame.u(g.ox, g.oy)
    fun glyphV(g: Glyph): Float = frame.v(g.ox, g.oy)
}

internal enum class Alignment { LEFT, CENTER, RIGHT, JUSTIFIED }

internal class TextParagraph(@JvmField val index: Int, @JvmField val lines: List<TextLine>) {
    @JvmField var text: String = ""
    val frame: Frame get() = lines[0].frame
    val bucket: Int get() = lines[0].bucket

    val left: Float get() = lines.minOf { it.u0 }
    val right: Float get() = lines.maxOf { it.u1 }
    val top: Float get() = lines.minOf { it.top }
    val bottom: Float get() = lines.maxOf { it.bottom }
}

/** Result of analysing one page. */
internal class PageLayout(
    @JvmField val lines: List<TextLine>,
    @JvmField val paragraphs: List<TextParagraph>,
    /** Glyphs that carry text, in reading order; `ordered[g.seq] === g`. */
    @JvmField val ordered: List<Glyph>,
    /** For each entry of [ordered], the line it belongs to. */
    @JvmField val lineOf: List<TextLine>,
)

/**
 * Turns the flat list of glyphs of a page into lines, words and paragraphs.
 *
 * PDF has no notion of lines or paragraphs: a page is just positioned glyphs. The approach here
 * relies on two signals:
 *  1. **content order** – virtually every producer writes text in reading order, so glyphs that
 *     follow each other in the content stream *and* sit next to each other on the same baseline
 *     belong to the same line, even across wide gaps;
 *  2. **geometry** – fragments that are not adjacent in the stream are only joined when they are
 *     about a word space apart, which keeps separate columns and table cells separate.
 */
internal object LayoutAnalyzer {

    private class Run(@JvmField val frame: Frame, @JvmField val bucket: Int) {
        @JvmField val glyphs = ArrayList<Glyph>()
        @JvmField var u0 = Float.MAX_VALUE
        @JvmField var u1 = -Float.MAX_VALUE
        @JvmField var lastV = 0f
        @JvmField var penEnd = 0f
        @JvmField var lastSize = 0f
        @JvmField var lastPen = 0f
        @JvmField var v = 0f
        @JvmField var size = 0f

        fun add(g: Glyph, gu: Float, gv: Float) {
            glyphs.add(g)
            u0 = min(u0, gu)
            u1 = max(u1, gu + g.width)
            lastV = gv
            penEnd = gu + g.penAdvance
            lastSize = g.size
            lastPen = g.penAdvance
        }
    }

    fun analyze(allGlyphs: List<Glyph>, geom: PageGeometry): PageLayout {
        val w = geom.displayWidth.toFloat()
        val h = geom.displayHeight.toFloat()

        // --- 1. runs: glyphs adjacent both in the stream and on the baseline -----------------
        val runs = ArrayList<Run>()
        var cur: Run? = null
        for (g in allGlyphs) {
            g.seq = -1
            g.duplicate = false
            // Ignore text that cannot be seen: outside the page, or clipped away by a form's box.
            if (g.clipped) continue
            val m = g.size
            if (g.ox < -m || g.oy < -m || g.ox > w + m || g.oy > h + m) continue

            val run = cur
            if (run != null && sameDirection(run.frame, g)) {
                val gu = run.frame.u(g.ox, g.oy)
                val gv = run.frame.v(g.ox, g.oy)
                val ref = max(run.lastSize, g.size)
                val gap = gu - run.penEnd
                if (abs(gv - run.lastV) <= 0.55f * ref &&
                    gap <= 1.8f * ref &&
                    gap >= -(abs(run.lastPen) + 0.3f * ref)
                ) {
                    run.add(g, gu, gv)
                    continue
                }
            }
            val frame = Frame(g.dirX, g.dirY)
            val fresh = Run(frame, bucketOf(g))
            fresh.add(g, frame.u(g.ox, g.oy), frame.v(g.ox, g.oy))
            runs.add(fresh)
            cur = fresh
        }
        for (r in runs) summarise(r)

        // --- 2. join runs that share a baseline and are about a word space apart -------------
        val merged = mergeRuns(runs)

        // --- 3. lines -------------------------------------------------------------------------
        val lines = ArrayList<TextLine>(merged.size)
        for (group in merged) lines.add(buildLine(group))
        lines.sortBy { it.firstStream }

        // --- 4. paragraphs --------------------------------------------------------------------
        val paragraphs = buildParagraphs(lines)

        // --- 5. reading order -----------------------------------------------------------------
        val ordered = ArrayList<Glyph>()
        val lineOf = ArrayList<TextLine>()
        for (p in paragraphs) {
            for (line in p.lines) {
                for (g in line.glyphs) {
                    if (g.duplicate || g.text.isEmpty()) continue
                    g.seq = ordered.size
                    ordered.add(g)
                    lineOf.add(line)
                }
            }
        }
        return PageLayout(lines, paragraphs, ordered, lineOf)
    }

    private fun bucketOf(g: Glyph): Int {
        val deg = Math.toDegrees(atan2(g.dirY.toDouble(), g.dirX.toDouble())).roundToInt()
        return ((deg % 360) + 360) % 360
    }

    private fun sameDirection(f: Frame, g: Glyph): Boolean = f.dirX * g.dirX + f.dirY * g.dirY > 0.9996f // ~1.6°

    private fun summarise(r: Run) {
        r.size = dominantSize(r.glyphs)
        r.v = dominantBaseline(r.glyphs, r.frame, r.size)
    }

    private fun dominantSize(glyphs: List<Glyph>): Float {
        if (glyphs.size == 1) return glyphs[0].size
        // Weighted by advance width so a long run of body text outweighs a superscript.
        val weights = HashMap<Int, Float>()
        for (g in glyphs) {
            val key = (g.size * 10f).roundToInt()
            weights[key] = (weights[key] ?: 0f) + max(g.width, 0.01f)
        }
        var bestKey = 0
        var best = -1f
        for ((k, v) in weights) if (v > best || (v == best && k > bestKey)) {
            best = v
            bestKey = k
        }
        return bestKey / 10f
    }

    private fun dominantBaseline(glyphs: List<Glyph>, frame: Frame, size: Float): Float {
        val vs = ArrayList<Float>()
        for (g in glyphs) if (abs(g.size - size) <= 0.06f * size + 0.05f) vs.add(frame.v(g.ox, g.oy))
        if (vs.isEmpty()) for (g in glyphs) vs.add(frame.v(g.ox, g.oy))
        vs.sort()
        return vs[vs.size / 2]
    }

    /** A run expressed in the common frame of its direction group. */
    private class RunItem(@JvmField val run: Run, @JvmField val v: Float, @JvmField val u0: Float, @JvmField val u1: Float)

    private fun mergeRuns(runs: List<Run>): List<List<Run>> {
        val byBucket = runs.groupBy { it.bucket }
        val result = ArrayList<List<Run>>()
        for ((_, list) in byBucket) {
            // Express every run of this direction in one common frame.
            val frame = list[0].frame
            val items = list.map { r ->
                val g0 = r.glyphs[0]
                // Offset between the run's own frame and the common one (they are near identical).
                val du = frame.u(g0.ox, g0.oy) - r.frame.u(g0.ox, g0.oy)
                val dv = frame.v(g0.ox, g0.oy) - r.frame.v(g0.ox, g0.oy)
                RunItem(r, r.v + dv, r.u0 + du, r.u1 + du)
            }.sortedWith(compareBy<RunItem> { it.v }.thenBy { it.u0 })

            val used = BooleanArray(items.size)
            for (i in items.indices) {
                if (used[i]) continue
                used[i] = true
                val group = ArrayList<RunItem>()
                group.add(items[i])
                var changed = true
                // Grow the group with any run on the same baseline that touches it.
                while (changed) {
                    changed = false
                    var j = i + 1
                    while (j < items.size) {
                        val cand = items[j]
                        if (cand.v - items[i].v > 0.6f * max(items[i].run.size, cand.run.size)) break
                        if (!used[j] && joins(group, cand)) {
                            used[j] = true
                            group.add(cand)
                            changed = true
                        }
                        j++
                    }
                }
                result.add(group.map { it.run })
            }
        }
        return result
    }

    private fun joins(group: List<RunItem>, c: RunItem): Boolean {
        for (m in group) {
            val ms = m.run.size
            val cs = c.run.size
            val ref = max(ms, cs)
            val ratio = if (ms > cs) ms / cs else cs / ms
            if (ratio > 1.7f) continue
            if (abs(m.v - c.v) > 0.2f * ref) continue
            val gap = if (c.u0 >= m.u1) c.u0 - m.u1 else if (m.u0 >= c.u1) m.u0 - c.u1 else 0f
            if (gap <= 0.75f * ref) return true
        }
        return false
    }

    private fun buildLine(group: List<Run>): TextLine {
        val frame = group[0].frame
        val all = ArrayList<Glyph>()
        for (r in group) all.addAll(r.glyphs)
        // Reading order along the baseline; stream order breaks ties (stacked glyphs).
        val sorted = all.sortedWith(compareBy<Glyph> { frame.u(it.ox, it.oy) }.thenBy { it.streamIndex })
        val line = TextLine(sorted, frame, group[0].bucket)

        line.size = dominantSize(sorted)
        line.v = dominantBaseline(sorted, frame, line.size)
        var u0 = Float.MAX_VALUE
        var u1 = -Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var first = Int.MAX_VALUE
        for (g in sorted) {
            val gu = frame.u(g.ox, g.oy)
            val gv = frame.v(g.ox, g.oy)
            u0 = min(u0, gu)
            u1 = max(u1, gu + g.width)
            top = min(top, gv - g.asc)
            bottom = max(bottom, gv - g.desc)
            first = min(first, g.streamIndex)
        }
        line.u0 = u0
        line.u1 = u1
        line.top = top
        line.bottom = bottom
        line.firstStream = first

        markDuplicates(line)
        buildWords(line)
        return line
    }

    /** Fake bold and drop shadows draw the same glyph twice, slightly offset. Keep only one as text. */
    private fun markDuplicates(line: TextLine) {
        val gl = line.glyphs
        for (i in gl.indices) {
            val g = gl[i]
            if (g.text.isBlank()) continue
            var j = i - 1
            var looked = 0
            while (j >= 0 && looked < 6) {
                val p = gl[j]
                j--
                if (p.duplicate) continue
                looked++
                val tol = 0.2f * max(g.size, p.size)
                if (p.text == g.text &&
                    abs(line.glyphU(p) - line.glyphU(g)) < tol &&
                    abs(line.glyphV(p) - line.glyphV(g)) < tol
                ) {
                    g.duplicate = true
                    break
                }
            }
        }
    }

    private fun buildWords(line: TextLine) {
        val words = ArrayList<Word>()
        val sb = StringBuilder()
        var wordStart = -1
        var wordEnd = -1
        val wordText = StringBuilder()
        var prev: Glyph? = null

        fun flush() {
            if (wordStart >= 0) {
                words.add(Word(wordStart, wordEnd, wordText.toString()))
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(wordText)
            }
            wordStart = -1
            wordText.setLength(0)
        }

        for (i in line.glyphs.indices) {
            val g = line.glyphs[i]
            if (g.duplicate || g.text.isEmpty()) continue
            if (g.text.isBlank()) {
                flush()
                prev = g
                continue
            }
            val p = prev
            if (p != null && wordStart >= 0) {
                val gap = line.glyphU(g) - (line.glyphU(p) + p.penAdvance)
                if (gap > spaceThreshold(p, g)) flush()
            }
            if (wordStart < 0) wordStart = i
            wordEnd = i
            wordText.append(g.text)
            prev = g
        }
        flush()
        line.words = words
        line.text = sb.toString()
    }

    /** Smallest gap between two glyphs that is read as a word space. */
    private fun spaceThreshold(p: Glyph, g: Glyph): Float {
        val size = max(p.size, g.size)
        val fromFont = 0.5f * (if (p.spaceWidth > 0f) p.spaceWidth else g.spaceWidth)
        return if (fromFont >= 0.1f * size && fromFont <= 0.2f * size) fromFont else 0.15f * size
    }

    // --- paragraphs -------------------------------------------------------------------------------

    private const val MAX_LEADING_EM = 2.45f

    private val BULLET = Regex("""^(?:[•◦▪▫■□●○‣⁃∙·\-–—*»›>]|\(?(?:\d{1,3}|[A-Za-z]|[ivxIVX]{1,4})[.)])$""")

    private fun buildParagraphs(lines: List<TextLine>): List<TextParagraph> {
        val textLines = lines.filter { it.words.isNotEmpty() }
        if (textLines.isEmpty()) return emptyList()
        val leadingBySize = commonLeading(textLines)

        // Pass 1 – blocks: consecutive lines in one column with one line spacing. Extra space
        // between paragraphs breaks the rhythm, so it already separates blocks.
        val blocks = ArrayList<ArrayList<TextLine>>()
        var block = ArrayList<TextLine>()
        var leading = Float.NaN
        for (line in textLines) {
            if (block.isNotEmpty()) {
                val prev = block[block.size - 1]
                if (chains(block, prev, line, leading, leadingBySize)) {
                    val dv = line.v - prev.v
                    leading = if (leading.isNaN()) dv else (leading * (block.size - 1) + dv) / block.size
                    block.add(line)
                    continue
                }
                blocks.add(block)
                block = ArrayList()
                leading = Float.NaN
            }
            block.add(line)
        }
        if (block.isNotEmpty()) blocks.add(block)

        // Pass 2 – paragraphs inside a block are only marked by indents and short last lines.
        val groups = ArrayList<List<TextLine>>()
        for (b in blocks) groups.addAll(splitBlock(b))

        // Pass 3 – text written by an earlier edit sits at the end of the content stream; put
        // it back where it is on the page.
        val ordered = restoreEditedOrder(groups)

        val paragraphs = ArrayList<TextParagraph>(ordered.size)
        for (group in ordered) {
            val p = TextParagraph(paragraphs.size, group)
            p.text = joinLines(group.map { it.text })
            for (line in group) line.paragraph = p.index
            paragraphs.add(p)
        }
        return paragraphs
    }

    private fun isFromEdit(group: List<TextLine>): Boolean {
        var any = false
        for (line in group) for (g in line.glyphs) {
            if (g.duplicate || g.text.isBlank()) continue
            if (!g.fromEdit) return false
            any = true
        }
        return any
    }

    private fun restoreEditedOrder(groups: List<List<TextLine>>): List<List<TextLine>> {
        val moved = groups.filter { isFromEdit(it) }
        if (moved.isEmpty()) return groups
        val result = ArrayList(groups.filter { !isFromEdit(it) })

        fun left(g: List<TextLine>) = g.minOf { it.u0 }
        fun right(g: List<TextLine>) = g.maxOf { it.u1 }
        fun top(g: List<TextLine>) = g.minOf { it.top }
        fun bottom(g: List<TextLine>) = g.maxOf { it.bottom }

        /** How much two paragraphs share a column: overlap relative to the wider one. */
        fun columnMatch(a: List<TextLine>, b: List<TextLine>): Float {
            val overlap = min(right(a), right(b)) - max(left(a), left(b))
            val wider = max(right(a) - left(a), right(b) - left(b))
            return if (wider <= 0f) 0f else (overlap / wider).coerceIn(0f, 1f)
        }

        // Upper paragraphs first, so a lower edited paragraph can be placed after an upper one.
        for (m in moved.sortedBy { top(it) }) {
            val bucket = m[0].bucket
            val slack = 0.5f * m[0].size
            var after = -1
            var afterBottom = -Float.MAX_VALUE
            var before = -1
            var beforeTop = Float.MAX_VALUE
            for (i in result.indices) {
                val r = result[i]
                if (r[0].bucket != bucket) continue
                if (min(right(r), right(m)) - max(left(r), left(m)) <= 0f) continue // another column
                if (bottom(r) <= top(m) + slack) {
                    // the last such paragraph in reading order that is closest above
                    if (bottom(r) >= afterBottom - 0.01f) {
                        afterBottom = bottom(r)
                        after = i
                    }
                } else if (top(r) >= bottom(m) - slack && top(r) < beforeTop) {
                    beforeTop = top(r)
                    before = i
                }
            }
            // Of the neighbour above and the neighbour below, trust the one that is more clearly
            // in the same column (a heading or footer spanning the page says little); if both
            // are, the nearer one.
            val useBefore = when {
                before < 0 -> false
                after < 0 -> true
                else -> {
                    val matchBefore = columnMatch(result[before], m)
                    val matchAfter = columnMatch(result[after], m)
                    when {
                        matchBefore > matchAfter + 0.1f -> true
                        matchAfter > matchBefore + 0.1f -> false
                        else -> beforeTop - bottom(m) < top(m) - afterBottom
                    }
                }
            }
            when {
                useBefore -> result.add(before, m)
                after >= 0 -> result.add(after + 1, m)
                else -> result.add(m)
            }
        }
        return result
    }

    private fun similarSize(a: TextLine, b: TextLine): Boolean {
        val r = if (a.size > b.size) a.size / b.size else b.size / a.size
        return r <= 1.15f
    }

    /** Most frequent baseline distance for each font size on the page, keyed by size in tenths. */
    private fun commonLeading(lines: List<TextLine>): Map<Int, Float> {
        val histogram = HashMap<Int, HashMap<Int, Int>>()
        for (i in 1 until lines.size) {
            val a = lines[i - 1]
            val b = lines[i]
            if (a.bucket != b.bucket || !similarSize(a, b)) continue
            val dv = b.v - a.v
            if (dv < 0.6f * a.size || dv > 3f * a.size) continue
            if (min(a.u1, b.u1) - max(a.u0, b.u0) <= 0f) continue
            val sizeKey = (a.size * 10f).roundToInt()
            val dvKey = (dv * 4f).roundToInt()
            val h = histogram.getOrPut(sizeKey) { HashMap() }
            h[dvKey] = (h[dvKey] ?: 0) + 1
        }
        val out = HashMap<Int, Float>()
        for ((sizeKey, h) in histogram) {
            var bestKey = 0
            var best = 0
            for ((k, n) in h) if (n > best || (n == best && k < bestKey)) {
                best = n
                bestKey = k
            }
            // A single observation is not a pattern.
            if (best >= 2) out[sizeKey] = bestKey / 4f
        }
        return out
    }

    /** True when [line] is the next line of the block ending in [prev]. */
    private fun chains(
        block: List<TextLine>, prev: TextLine, line: TextLine,
        leading: Float, leadingBySize: Map<Int, Float>,
    ): Boolean {
        if (line.bucket != prev.bucket) return false
        if (!similarSize(prev, line)) return false
        val size = max(prev.size, line.size)

        // Must be the next line down…
        val dv = line.v - prev.v
        if (dv < 0.6f * size) return false
        // Even double-spaced text stays below about 2.4 em; anything looser is separate lines.
        if (dv > MAX_LEADING_EM * size) return false
        // …at the block's own rhythm.
        if (!leading.isNaN()) {
            if (abs(dv - leading) > max(0.12f * leading, 0.75f)) return false
        } else {
            val common = leadingBySize[(prev.size * 10f).roundToInt()]
            val limit = if (common != null) max(1.2f * common, 1.25f * size) else 1.65f * size
            if (dv > limit) return false
        }

        // Must sit in the same column.
        val left = block.minOf { it.u0 }
        val right = block.maxOf { it.u1 }
        val overlap = min(line.u1, right) - max(line.u0, left)
        if (overlap < 0.3f * min(line.width, right - left) && abs(line.u0 - left) > 2f * size) return false
        return true
    }

    private fun splitBlock(block: List<TextLine>): List<List<TextLine>> {
        if (block.size == 1) return listOf(block)
        val size = block.maxOf { it.size }

        // Centred text: one axis, differing widths. Line breaks carry no paragraph meaning.
        var axisShared = true
        var widthsDiffer = false
        for (i in 1 until block.size) {
            if (abs(block[i].centre - block[i - 1].centre) > 0.6f * size) axisShared = false
            if (abs(block[i].u0 - block[i - 1].u0) > 0.6f * size) widthsDiffer = true
        }
        if (axisShared && widthsDiffer) return listOf(block)
        // Right aligned text likewise.
        val rightEdge = block[0].u1
        if (widthsDiffer && block.all { abs(it.u1 - rightEdge) <= 0.3f * size }) return listOf(block)

        val blockRight = block.maxOf { it.u1 }
        val out = ArrayList<List<TextLine>>()
        var current = ArrayList<TextLine>()
        var curLeft = 0f
        for (line in block) {
            if (current.isEmpty()) {
                current.add(line)
                curLeft = line.u0
                continue
            }
            val prev = current[current.size - 1]
            if (startsNewParagraph(current, prev, line, curLeft, blockRight, size)) {
                out.add(current)
                current = ArrayList()
                current.add(line)
                curLeft = line.u0
            } else {
                // The first line may be indented or hanging: lines 2+ define the left edge.
                curLeft = if (current.size == 1) line.u0 else min(curLeft, line.u0)
                current.add(line)
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun startsNewParagraph(
        para: List<TextLine>, prev: TextLine, line: TextLine,
        curLeft: Float, blockRight: Float, size: Float,
    ): Boolean {
        // A list marker at the paragraph's left edge starts a new item.
        val firstWord = line.words.firstOrNull()?.text ?: ""
        if (BULLET.matches(firstWord) && line.u0 <= curLeft + 0.3f * size) return true

        if (para.size == 1) {
            // Only one line so far: it may be indented (normal first line) or stick out to the
            // left (hanging indent, bullets). Anything further off is a different block.
            if (abs(line.u0 - prev.u0) > 4f * size) return true
        } else {
            if (line.u0 < curLeft - 0.6f * size) return true   // back out to the left: new block
            if (line.u0 > curLeft + 0.6f * size) return true   // indented: new paragraph
        }

        // The classic test: if the previous line stopped early although the next word would have
        // fitted, the break was deliberate, i.e. the paragraph ended there.
        val firstWordWidth = line.words.firstOrNull()?.let { wd ->
            val a = line.glyphs[wd.first]
            val b = line.glyphs[wd.last]
            line.glyphU(b) + b.width - line.glyphU(a)
        } ?: 0f
        val room = blockRight - prev.u1
        return room > firstWordWidth + 0.35f * size
    }

    /**
     * Joins the lines of a paragraph into flowing text. A line ending in a hyphen that splits a
     * word ("exam-" / "ple") is re-joined; everything else is separated by a space.
     */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val next = raw.trim()
            if (next.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(next)
                continue
            }
            val n = sb.length
            if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter() && next[0].isLowerCase()) {
                sb.setLength(n - 1)
                sb.append(next)
            } else {
                sb.append(' ').append(next)
            }
        }
        return sb.toString()
    }
}
