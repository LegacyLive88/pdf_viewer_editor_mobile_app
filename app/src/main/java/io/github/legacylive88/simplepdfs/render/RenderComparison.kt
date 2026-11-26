package io.github.legacylive88.simplepdfs.render

import io.github.legacylive88.simplepdfs.pdfcore.PageChange
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Compares two pictures of the same page, before and after an edit.
 *
 * Editing a paragraph rewrites the page's content. Whatever the engine did, the page must look
 * exactly as before everywhere except where the paragraph was and where its new text is. This
 * is the last line of defence against an edit damaging some other part of a document: it does
 * not rely on the engine being right, only on the pictures.
 */
object RenderComparison {

    /** Two colours further apart than this (in any channel) count as different. */
    private const val COLOUR_TOLERANCE = 40

    /** Space around the edited text that is not compared, in points: glyphs overhang their boxes. */
    const val MARGIN_POINTS = 6f

    /**
     * Number of pixels that visibly differ outside the masked rectangles.
     *
     * @param masked rectangles as left, top, right, bottom in pixels (right/bottom exclusive)
     */
    fun differingPixels(before: IntArray, after: IntArray, width: Int, height: Int, masked: List<IntArray>): Int {
        require(before.size == width * height && after.size == before.size) { "pictures must have the same size" }
        val skip = BooleanArray(width * height)
        for (m in masked) {
            val l = m[0].coerceIn(0, width)
            val t = m[1].coerceIn(0, height)
            val r = m[2].coerceIn(0, width)
            val b = m[3].coerceIn(0, height)
            for (y in t until b) java.util.Arrays.fill(skip, y * width + l, y * width + r, true)
        }
        var count = 0
        for (i in before.indices) {
            if (skip[i]) continue
            val a = before[i]
            val c = after[i]
            if (a == c) continue
            if (abs(((a shr 16) and 0xFF) - ((c shr 16) and 0xFF)) > COLOUR_TOLERANCE ||
                abs(((a shr 8) and 0xFF) - ((c shr 8) and 0xFF)) > COLOUR_TOLERANCE ||
                abs((a and 0xFF) - (c and 0xFF)) > COLOUR_TOLERANCE
            ) count++
        }
        return count
    }

    /**
     * How many differing pixels are tolerated: a handful, for rounding at the edges of
     * anti-aliased glyphs that share an operator with removed text. Real damage – a lost
     * picture, shifted lines – changes thousands.
     */
    fun tolerated(width: Int, height: Int): Int = max(24, width * height / 4000)

    /**
     * The area of a change as pixel rectangles to exclude from the comparison.
     *
     * @param scale pixels per point of the pictures
     * @param unitScale factor from the change's coordinates to the renderer's points (1 unless
     *   the two measure the page in different units)
     */
    fun mask(change: PageChange, scale: Float, unitScale: Float): List<IntArray> = change.area.map { q ->
        val k = scale * unitScale
        val margin = MARGIN_POINTS * scale
        intArrayOf(
            floor(q.left * k - margin).toInt(),
            floor(q.top * k - margin).toInt(),
            ceil(q.right * k + margin).toInt(),
            ceil(q.bottom * k + margin).toInt(),
        )
    }
}
