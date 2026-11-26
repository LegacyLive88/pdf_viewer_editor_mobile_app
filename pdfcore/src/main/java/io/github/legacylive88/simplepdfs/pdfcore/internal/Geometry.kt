package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.pdmodel.PDPage
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import kotlin.math.abs

/**
 * 2D affine transform using the PDF convention:
 * ```
 * x' = a*x + c*y + e
 * y' = b*x + d*y + f
 * ```
 */
internal class Affine(
    @JvmField val a: Double, @JvmField val b: Double,
    @JvmField val c: Double, @JvmField val d: Double,
    @JvmField val e: Double, @JvmField val f: Double,
) {
    fun x(px: Double, py: Double): Double = a * px + c * py + e
    fun y(px: Double, py: Double): Double = b * px + d * py + f

    /** Applies only the linear part (for direction vectors). */
    fun vx(dx: Double, dy: Double): Double = a * dx + c * dy
    fun vy(dx: Double, dy: Double): Double = b * dx + d * dy

    val determinant: Double get() = a * d - b * c

    fun isIdentity(eps: Double = 1e-6): Boolean =
        abs(a - 1) < eps && abs(b) < eps && abs(c) < eps && abs(d - 1) < eps && abs(e) < eps && abs(f) < eps

    fun inverse(): Affine {
        val det = determinant
        require(abs(det) > 1e-12) { "Matrix is not invertible" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        val ie = (c * f - d * e) / det
        val iff = (b * e - a * f) / det
        return Affine(ia, ib, ic, id, ie, iff)
    }

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}

/**
 * Maps between PDF *user space* (what content streams and annotations are written in: origin
 * bottom-left-ish, y up, unrotated) and *display space* (what the user sees and what
 * PdfRenderer draws: origin top-left of the visible page, y down, `/Rotate` applied).
 *
 * The visible page area is the crop box clipped to the media box, which is also what PDFium
 * (and therefore Android's PdfRenderer) uses.
 */
internal class PageGeometry(
    @JvmField val llx: Double, @JvmField val lly: Double,
    @JvmField val urx: Double, @JvmField val ury: Double,
    /** Normalised to 0, 90, 180 or 270. */
    @JvmField val rotation: Int,
) {
    val displayWidth: Double = if (rotation == 90 || rotation == 270) ury - lly else urx - llx
    val displayHeight: Double = if (rotation == 90 || rotation == 270) urx - llx else ury - lly

    val userToDisplay: Affine = when (rotation) {
        90 -> Affine(0.0, 1.0, 1.0, 0.0, -lly, -llx)      // dx = uy - lly ; dy = ux - llx
        180 -> Affine(-1.0, 0.0, 0.0, 1.0, urx, -lly)     // dx = urx - ux ; dy = uy - lly
        270 -> Affine(0.0, -1.0, -1.0, 0.0, ury, urx)     // dx = ury - uy ; dy = urx - ux
        else -> Affine(1.0, 0.0, 0.0, -1.0, -llx, ury)    // dx = ux - llx ; dy = ury - uy
    }

    val displayToUser: Affine = userToDisplay.inverse()

    companion object {
        fun of(page: PDPage): PageGeometry {
            val crop = page.cropBox // PDFBox already clips the crop box to the media box
            var rot = page.rotation % 360
            if (rot < 0) rot += 360
            if (rot % 90 != 0) rot = 0
            return PageGeometry(
                crop.lowerLeftX.toDouble(), crop.lowerLeftY.toDouble(),
                crop.upperRightX.toDouble(), crop.upperRightY.toDouble(),
                rot,
            )
        }
    }
}

/**
 * A text-aligned coordinate frame in display space: `u` runs along the baseline in reading
 * direction, `v` runs perpendicular to it towards the *next line* (visually downwards for
 * upright text).
 */
internal class Frame(@JvmField val dirX: Float, @JvmField val dirY: Float) {
    fun u(x: Float, y: Float): Float = x * dirX + y * dirY
    fun v(x: Float, y: Float): Float = -x * dirY + y * dirX
    fun x(u: Float, v: Float): Float = u * dirX - v * dirY
    fun y(u: Float, v: Float): Float = u * dirY + v * dirX

    /** Builds the display-space quad for the frame-aligned box [u0,u1] x [vTop,vBottom]. */
    fun quad(u0: Float, u1: Float, vTop: Float, vBottom: Float): Quad = Quad(
        x(u0, vTop), y(u0, vTop),
        x(u1, vTop), y(u1, vTop),
        x(u1, vBottom), y(u1, vBottom),
        x(u0, vBottom), y(u0, vBottom),
    )

    companion object {
        /** Derives the frame a quad was built in from its corner order. */
        fun of(q: Quad): Frame {
            var dx = q.x2 - q.x1
            var dy = q.y2 - q.y1
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) return Frame(1f, 0f)
            dx /= len
            dy /= len
            return Frame(dx, dy)
        }
    }
}

/** Extent of a set of points measured in a [Frame]. */
internal class FrameBox(@JvmField val u0: Float, @JvmField val u1: Float, @JvmField val v0: Float, @JvmField val v1: Float) {
    val width: Float get() = u1 - u0
    val height: Float get() = v1 - v0

    companion object {
        fun of(frame: Frame, q: Quad): FrameBox {
            val us = floatArrayOf(frame.u(q.x1, q.y1), frame.u(q.x2, q.y2), frame.u(q.x3, q.y3), frame.u(q.x4, q.y4))
            val vs = floatArrayOf(frame.v(q.x1, q.y1), frame.v(q.x2, q.y2), frame.v(q.x3, q.y3), frame.v(q.x4, q.y4))
            return FrameBox(us.min(), us.max(), vs.min(), vs.max())
        }
    }
}
