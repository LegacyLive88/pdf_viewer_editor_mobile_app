package io.github.legacylive88.simplepdfs.pdfcore

import io.github.legacylive88.simplepdfs.pdfcore.internal.Affine
import io.github.legacylive88.simplepdfs.pdfcore.internal.Frame
import io.github.legacylive88.simplepdfs.pdfcore.internal.FrameBox
import io.github.legacylive88.simplepdfs.pdfcore.internal.Markup
import io.github.legacylive88.simplepdfs.pdfcore.internal.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinate mathematics everything else relies on. Pure logic. */
class GeometryTest {

    private fun assertPoint(expectedX: Double, expectedY: Double, t: Affine, x: Double, y: Double) {
        assertEquals("x", expectedX, t.x(x, y), 1e-9)
        assertEquals("y", expectedY, t.y(x, y), 1e-9)
    }

    @Test
    fun anUnrotatedPageFlipsTheYAxis() {
        val g = PageGeometry(0.0, 0.0, 612.0, 792.0, 0)
        assertEquals(612.0, g.displayWidth, 0.0)
        assertEquals(792.0, g.displayHeight, 0.0)
        assertPoint(0.0, 792.0, g.userToDisplay, 0.0, 0.0)     // bottom-left of the paper is bottom-left on screen
        assertPoint(0.0, 0.0, g.userToDisplay, 0.0, 792.0)     // top-left
        assertPoint(612.0, 0.0, g.userToDisplay, 612.0, 792.0) // top-right
    }

    @Test
    fun rotatedPagesPutTheRightCornerAtTheTopLeft() {
        // 90 degrees clockwise: the bottom-left of the paper is shown at the top-left
        val r90 = PageGeometry(0.0, 0.0, 612.0, 792.0, 90)
        assertEquals(792.0, r90.displayWidth, 0.0)
        assertEquals(612.0, r90.displayHeight, 0.0)
        assertPoint(0.0, 0.0, r90.userToDisplay, 0.0, 0.0)
        assertPoint(792.0, 0.0, r90.userToDisplay, 0.0, 792.0)
        assertPoint(0.0, 612.0, r90.userToDisplay, 612.0, 0.0)

        // upside down: the top-right of the paper is shown at the bottom-left
        val r180 = PageGeometry(0.0, 0.0, 612.0, 792.0, 180)
        assertPoint(612.0, 0.0, r180.userToDisplay, 0.0, 0.0)
        assertPoint(0.0, 792.0, r180.userToDisplay, 612.0, 792.0)

        // 270: the top-right of the paper is shown at the top-left
        val r270 = PageGeometry(0.0, 0.0, 612.0, 792.0, 270)
        assertPoint(0.0, 0.0, r270.userToDisplay, 612.0, 792.0)
        assertPoint(792.0, 612.0, r270.userToDisplay, 0.0, 0.0)
    }

    @Test
    fun aCropBoxThatDoesNotStartAtTheOriginIsTakenIntoAccount() {
        val g = PageGeometry(36.0, 60.0, 500.0, 760.0, 0)
        assertEquals(464.0, g.displayWidth, 0.0)
        assertEquals(700.0, g.displayHeight, 0.0)
        assertPoint(0.0, 0.0, g.userToDisplay, 36.0, 760.0)
        assertPoint(464.0, 700.0, g.userToDisplay, 500.0, 60.0)
        val r = PageGeometry(36.0, 60.0, 500.0, 760.0, 90)
        assertEquals(700.0, r.displayWidth, 0.0)
        assertPoint(0.0, 0.0, r.userToDisplay, 36.0, 60.0)
    }

    @Test
    fun everyTransformCanBeUndone() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val g = PageGeometry(36.0, 60.0, 500.0, 760.0, rotation)
            for ((x, y) in listOf(100.0 to 200.0, 36.0 to 60.0, 499.5 to 700.25)) {
                val dx = g.userToDisplay.x(x, y)
                val dy = g.userToDisplay.y(x, y)
                assertEquals("rotation $rotation", x, g.displayToUser.x(dx, dy), 1e-9)
                assertEquals("rotation $rotation", y, g.displayToUser.y(dx, dy), 1e-9)
                // nothing visible falls outside the displayed page
                assertTrue(dx >= -1e-9 && dx <= g.displayWidth + 1e-9 && dy >= -1e-9 && dy <= g.displayHeight + 1e-9)
            }
        }
        val m = Affine(2.0, 0.5, -0.25, 3.0, 10.0, -20.0)
        val inv = m.inverse()
        assertEquals(7.0, inv.x(m.x(7.0, 9.0), m.y(7.0, 9.0)), 1e-9)
        assertEquals(9.0, inv.y(m.x(7.0, 9.0), m.y(7.0, 9.0)), 1e-9)
        assertTrue(Affine.IDENTITY.isIdentity())
        assertFalse(m.isIdentity())
    }

    @Test
    fun aTextFrameMeasuresAlongAndAcrossTheBaseline() {
        // text running straight down the screen (as on a page rotated by 90 degrees)
        val down = Frame(0f, 1f)
        assertEquals(50f, down.u(10f, 50f), 1e-4f)   // along the line = down the screen
        assertEquals(-10f, down.v(10f, 50f), 1e-4f)  // "next line" is towards the left
        val q = down.quad(0f, 100f, -20f, -8f)
        val box = FrameBox.of(down, q)
        assertEquals(0f, box.u0, 1e-4f); assertEquals(100f, box.u1, 1e-4f)
        assertEquals(-20f, box.v0, 1e-4f); assertEquals(-8f, box.v1, 1e-4f)
        // the frame can be recovered from the quad it produced
        val again = Frame.of(q)
        assertEquals(0f, again.dirX, 1e-4f); assertEquals(1f, again.dirY, 1e-4f)
        assertTrue(q.contains(14f, 50f))
        assertFalse(q.contains(25f, 50f))
    }

    // --- cutting a selection out of a highlight ---------------------------------------------------

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad(l, t, r, t, r, b, l, b)

    @Test
    fun removingTheMiddleOfAHighlightLeavesBothEnds() {
        val left = Markup.subtract(listOf(rect(100f, 50f, 300f, 62f)), listOf(rect(150f, 50f, 200f, 62f)))!!
        assertEquals(2, left.size)
        assertEquals(100f, left[0].left, 1e-3f); assertEquals(150f, left[0].right, 1e-3f)
        assertEquals(200f, left[1].left, 1e-3f); assertEquals(300f, left[1].right, 1e-3f)
        assertEquals(50f, left[0].top, 1e-3f); assertEquals(62f, left[0].bottom, 1e-3f)
    }

    @Test
    fun removingAnEndOrEverything() {
        val start = Markup.subtract(listOf(rect(100f, 50f, 300f, 62f)), listOf(rect(90f, 50f, 180f, 62f)))!!
        assertEquals(1, start.size)
        assertEquals(180f, start[0].left, 1e-3f)
        val all = Markup.subtract(listOf(rect(100f, 50f, 300f, 62f)), listOf(rect(100f, 50f, 300f, 62f)))
        assertNotNull(all)
        assertTrue(all!!.isEmpty())
    }

    @Test
    fun aSelectionOnAnotherLineOrBesideTheHighlightTouchesNothing() {
        val h = listOf(rect(100f, 50f, 300f, 62f))
        assertNull("the line below", Markup.subtract(h, listOf(rect(100f, 65f, 300f, 77f))))
        assertNull("further right on the same line", Markup.subtract(h, listOf(rect(310f, 50f, 400f, 62f))))
        // line boxes of neighbouring lines overlap a little; that is not "the same line"
        assertNull(Markup.subtract(h, listOf(rect(100f, 59f, 300f, 71f))))
    }

    @Test
    fun leftoversThinnerThanACharacterAreDropped() {
        // the highlight is padded 3 points beyond the words that are being un-highlighted
        val left = Markup.subtract(listOf(rect(97f, 50f, 303f, 62f)), listOf(rect(100f, 50f, 300f, 62f)))!!
        assertTrue(left.isEmpty())
        // 8 points is a character's worth and stays
        assertEquals(1, Markup.subtract(listOf(rect(100f, 50f, 308f, 62f)), listOf(rect(100f, 50f, 300f, 62f)))!!.size)
    }

    @Test
    fun trimmingWorksOnSidewaysText() {
        val down = Frame(0f, 1f)
        val highlight = down.quad(100f, 300f, -30f, -18f)
        val left = Markup.subtract(listOf(highlight), listOf(down.quad(150f, 200f, -30f, -18f)))!!
        assertEquals(2, left.size)
        // the pieces are stacked along the screen's y axis, which is the direction of the text
        assertEquals(100f, left[0].top, 1e-3f); assertEquals(150f, left[0].bottom, 1e-3f)
        assertEquals(200f, left[1].top, 1e-3f); assertEquals(300f, left[1].bottom, 1e-3f)
    }

    @Test
    fun severalSelectionBoxesAreCutOneAfterTheOther() {
        val highlight = listOf(rect(100f, 50f, 300f, 62f), rect(72f, 65f, 250f, 77f))
        val selection = listOf(rect(200f, 50f, 300f, 62f), rect(72f, 65f, 120f, 77f))
        val left = Markup.subtract(highlight, selection)!!
        assertEquals(2, left.size)
        assertEquals(200f, left[0].right, 1e-3f)
        assertEquals(120f, left[1].left, 1e-3f)
    }
}
