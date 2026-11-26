package io.github.legacylive88.simplepdfs

import io.github.legacylive88.simplepdfs.pdfcore.PageChange
import io.github.legacylive88.simplepdfs.pdfcore.PageSize
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import io.github.legacylive88.simplepdfs.render.RenderComparison
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture comparison behind "an edit must not change anything else on the page".
 * Plain arithmetic on pixel arrays, so it runs anywhere.
 */
class RenderComparisonTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(width: Int, height: Int) = IntArray(width * height) { white }

    private fun fill(pixels: IntArray, width: Int, l: Int, t: Int, r: Int, b: Int, colour: Int) {
        for (y in t until b) for (x in l until r) pixels[y * width + x] = colour
    }

    @Test
    fun identicalPicturesHaveNoDifferences() {
        val a = page(100, 80)
        fill(a, 100, 10, 10, 60, 14, black)
        assertEquals(0, RenderComparison.differingPixels(a, a.clone(), 100, 80, emptyList()))
    }

    @Test
    fun changesInsideTheEditedAreaAreExpectedAndIgnored() {
        val before = page(100, 80)
        val after = page(100, 80)
        fill(before, 100, 10, 10, 60, 14, black) // the old paragraph
        fill(after, 100, 10, 10, 45, 14, black)  // the new, shorter one
        assertEquals(15 * 4, RenderComparison.differingPixels(before, after, 100, 80, emptyList()))
        assertEquals(0, RenderComparison.differingPixels(before, after, 100, 80, listOf(intArrayOf(8, 8, 62, 16))))
    }

    @Test
    fun aChangeAnywhereElseIsCounted() {
        val before = page(100, 80)
        val after = page(100, 80)
        fill(before, 100, 10, 10, 60, 14, black)
        fill(after, 100, 10, 10, 45, 14, black)
        fill(before, 100, 20, 50, 80, 54, black) // another line of the page…
        fill(after, 100, 20, 51, 80, 55, black)  // …has moved down by one pixel
        val different = RenderComparison.differingPixels(before, after, 100, 80, listOf(intArrayOf(8, 8, 62, 16)))
        assertEquals("one row disappeared and one appeared", 60 * 2, different)
        assertTrue(different > RenderComparison.tolerated(100, 80))
    }

    @Test
    fun faintDifferencesFromAntiAliasingAreNotDifferences() {
        val before = page(10, 10)
        val after = page(10, 10)
        after[5] = 0xFFE8E8E8.toInt()  // 23 levels darker: rounding noise
        after[6] = 0xFFC0C0C0.toInt()  // 63 levels darker: visible
        after[7] = 0xFFFFFFD0.toInt()  // only blue differs, by 47: visible
        assertEquals(2, RenderComparison.differingPixels(before, after, 10, 10, emptyList()))
    }

    @Test
    fun aFewStrayPixelsAreToleratedButNotMany() {
        assertEquals(24, RenderComparison.tolerated(100, 80))
        // a 520 x 735 picture of an A4 page tolerates about 95 pixels: a few glyph edges
        assertEquals(95, RenderComparison.tolerated(520, 735))
    }

    @Test
    fun theMaskCoversTheChangedTextWithAMarginAndIsClampedToThePicture() {
        val change = PageChange(0, PageSize(612f, 792f), true, listOf(Quad(72f, 140f, 410f, 140f, 410f, 200f, 72f, 200f)))
        val mask = RenderComparison.mask(change, scale = 0.5f, unitScale = 1f)
        // 6 points of margin are 3 pixels at this scale
        assertArrayEquals(intArrayOf(33, 67, 208, 103), mask[0])
        // coordinates in another unit are converted first, and the margin with them
        assertArrayEquals(intArrayOf(66, 134, 416, 206), RenderComparison.mask(change, 0.5f, 2f)[0])

        // large type overhangs further, so its margin is wider – up to a limit
        assertEquals(6f, PageChange.overhangFor(11f), 0f)
        assertEquals(16.8f, PageChange.overhangFor(48f), 0.001f)
        assertEquals(24f, PageChange.overhangFor(200f), 0f)
        val headline = PageChange(0, PageSize(612f, 792f), true, change.area, PageChange.overhangFor(48f))
        assertArrayEquals(intArrayOf(27, 61, 214, 109), RenderComparison.mask(headline, 0.5f, 1f)[0])

        // a mask that sticks out of the picture is simply cut off
        val before = page(20, 20)
        val after = page(20, 20)
        fill(after, 20, 0, 0, 20, 5, black)
        assertEquals(0, RenderComparison.differingPixels(before, after, 20, 20, listOf(intArrayOf(-50, -50, 500, 5))))
    }
}
