package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetStrokingDeviceRGBColor
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One glyph drawn on a page, with both its on-screen geometry (display space) and its exact
 * origin in the PDF content (which stream, which operator, which glyph within that operator).
 * The second part is what makes real text editing possible: a paragraph can be removed by
 * deleting exactly its glyphs from the content streams.
 */
internal class Glyph(
    /** Unicode text of this glyph; empty for glyphs that carry no text (control characters). */
    @JvmField val text: String,
    /** Baseline origin, display space. */
    @JvmField val ox: Float, @JvmField val oy: Float,
    /** Unit vector along the baseline in reading direction, display space. */
    @JvmField val dirX: Float, @JvmField val dirY: Float,
    /** Advance width of the glyph itself along [dirX],[dirY] (>= 0). */
    @JvmField val width: Float,
    /** How far the pen moved after this glyph, including character/word spacing. */
    @JvmField val penAdvance: Float,
    /** Em size in display units (font size after all scaling). */
    @JvmField val size: Float,
    /** Extent above (positive) and below (negative) the baseline, display units. */
    @JvmField val asc: Float, @JvmField val desc: Float,
    /** Width of a space in this font at this size, display units (0 if unknown). */
    @JvmField val spaceWidth: Float,
    @JvmField val font: PDFont,
    /** 0xRRGGBB fill colour (stroke colour for stroke-only text). */
    @JvmField val color: Int,
    /** PDF text rendering mode (0 fill … 3 invisible). */
    @JvmField val renderMode: Int,
    /** False when the PDF gives no Unicode value for this glyph ([text] is then U+FFFD). */
    @JvmField val mapped: Boolean,
    /** True for vertically written text. */
    @JvmField val vertical: Boolean,
    // --- where the glyph comes from -------------------------------------------------------
    /** Index into [GlyphExtractor.contexts]. */
    @JvmField val ctx: Int,
    /** Index of the text-showing operator within its stream (counting operators only). */
    @JvmField val op: Int,
    /** Ordinal of this glyph among all glyphs shown by that operator. */
    @JvmField val ord: Int,
    /** `TJ` adjustment (thousandths of text space) that moves the pen exactly as this glyph did. */
    @JvmField val tjAdjust: Float,
    /** Position in content-stream order across the whole page. */
    @JvmField val streamIndex: Int,
    /** True when the glyph lies outside the bounding box of a form it is drawn in, i.e. is not shown. */
    @JvmField val clipped: Boolean,
    /** True for text this app wrote when a paragraph was edited (see [GlyphExtractor.EDIT_TAG]). */
    @JvmField val fromEdit: Boolean,
) {
    /** Set by the layout pass for glyphs that merely repeat another glyph (fake bold, shadows). */
    @JvmField var duplicate: Boolean = false

    /** Linear reading-order index assigned by the layout pass (-1 if not part of the layout). */
    @JvmField var seq: Int = -1
}

/** A content stream that was processed: the page itself (index 0) or a nested form XObject. */
internal class StreamContext(
    @JvmField val id: Int,
    /** Parent context index, -1 for the page. */
    @JvmField val parent: Int,
    /** Index of the `Do` operator in the parent stream that invoked this form. */
    @JvmField val parentOp: Int,
    /** The form's stream; null for the page. */
    @JvmField val stream: COSStream?,
    /** Resources that were in effect while this stream was processed. */
    @JvmField val resources: PDResources?,
) {
    @JvmField var opIndex: Int = -1
    @JvmField var dispatchDepth: Int = 0

    /** One entry per open marked-content sequence: true if it is one of this app's edit blocks. */
    @JvmField val marks: ArrayList<Boolean> = ArrayList()
    @JvmField var inEdit: Boolean = false

    /** Font that was current for each text-showing operator that produced glyphs. */
    @JvmField val fontByOp: HashMap<Int, PDFont> = HashMap()
}

/** Graphics state left over at the very end of the page content (see [GlyphExtractor.endState]). */
internal class EndState(
    /** Number of `q` that were never closed by a `Q`. */
    @JvmField val unclosedSaves: Int,
    /** CTM in effect after those unclosed saves are popped. */
    @JvmField val baseCtm: Affine,
    /** True when alpha, blend mode or a soft mask differ from the defaults in that state. */
    @JvmField val needsGraphicsStateReset: Boolean,
)

/**
 * Walks a page's content (including nested form XObjects) and records every glyph.
 *
 * This deliberately does not use PDFBox's PDFTextStripper: the stripper's geometry is a set of
 * historical heuristics, and it does not expose which operator each glyph came from.
 */
internal class GlyphExtractor(private val page: PDPage, private val geom: PageGeometry) : PDFStreamEngine() {

    val glyphs: ArrayList<Glyph> = ArrayList(2048)
    val contexts: ArrayList<StreamContext> = ArrayList()
    lateinit var endState: EndState
        private set

    private val stack = ArrayList<StreamContext>()
    private var streamCounter = 0
    private val u2d = geom.userToDisplay

    init {
        addOperator(BeginText())
        addOperator(Concatenate())
        addOperator(DrawObject()) // text variant: follows form XObjects, ignores images
        addOperator(EndText())
        addOperator(SetGraphicsStateParameters())
        addOperator(Save())
        addOperator(Restore())
        addOperator(NextLine())
        addOperator(SetCharSpacing())
        addOperator(MoveText())
        addOperator(MoveTextSetLeading())
        addOperator(SetFontAndSize())
        addOperator(ShowText())
        addOperator(ShowTextAdjusted())
        addOperator(SetTextLeading())
        addOperator(SetMatrix())
        addOperator(SetTextRenderingMode())
        addOperator(SetTextRise())
        addOperator(SetWordSpacing())
        addOperator(SetTextHorizontalScaling())
        addOperator(ShowTextLine())
        addOperator(ShowTextLineAndSpace())
        // Colours, so replacement text can keep the original colour.
        addOperator(SetStrokingColorSpace())
        addOperator(SetNonStrokingColorSpace())
        addOperator(SetStrokingColor())
        addOperator(SetNonStrokingColor())
        addOperator(SetStrokingColorN())
        addOperator(SetNonStrokingColorN())
        addOperator(SetStrokingDeviceGrayColor())
        addOperator(SetNonStrokingDeviceGrayColor())
        addOperator(SetStrokingDeviceRGBColor())
        addOperator(SetNonStrokingDeviceRGBColor())
        addOperator(SetStrokingDeviceCMYKColor())
        addOperator(SetNonStrokingDeviceCMYKColor())
    }

    /** Processes the page. Call once. */
    fun run() {
        val root = StreamContext(0, -1, -1, null, page.resources)
        contexts.add(root)
        stack.add(root)
        processPage(page)
        stack.clear()
        endState = EndState(pageDepth, baseCtm, baseNeedsReset)
    }

    // The interpreter restores its graphics stack when it finishes a stream, so what the page
    // content leaves behind has to be tracked while it runs: how many `q` are still open, and
    // the state underneath them (which only operators outside any q/Q pair can change).
    private var pageDepth = 0
    private var baseCtm = Affine.IDENTITY
    private var baseNeedsReset = false

    private fun currentCtm(): Affine {
        val m = graphicsState.currentTransformationMatrix
        return Affine(
            m.scaleX.toDouble(), m.shearY.toDouble(),
            m.shearX.toDouble(), m.scaleY.toDouble(),
            m.translateX.toDouble(), m.translateY.toDouble(),
        )
    }

    private fun currentStateNeedsReset(): Boolean {
        val gs: PDGraphicsState = graphicsState
        val blend = gs.blendMode
        return abs(gs.nonStrokeAlphaConstant - 1.0) > 1e-4 ||
            abs(gs.alphaConstant - 1.0) > 1e-4 ||
            gs.softMask != null ||
            (blend != null && blend !== BlendMode.NORMAL && blend !== BlendMode.COMPATIBLE)
    }

    // --- stream / operator bookkeeping --------------------------------------------------------

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
        val ctx = stack.last()
        // Operators such as ' and " are implemented by dispatching other operators; only the
        // outermost dispatch corresponds to a token in the stream.
        val real = ctx.dispatchDepth == 0
        val name = operator.name
        if (real) {
            ctx.opIndex++
            if (name == "BMC" || name == "BDC") {
                val tag = operands?.firstOrNull() as? COSName
                ctx.marks.add(name == "BMC" && tag?.name == EDIT_TAG)
                ctx.inEdit = ctx.marks.contains(true)
            } else if (name == "EMC" && ctx.marks.isNotEmpty()) {
                ctx.marks.removeAt(ctx.marks.size - 1)
                ctx.inEdit = ctx.marks.contains(true)
            }
        }
        ctx.dispatchDepth++
        try {
            super.processOperator(operator, operands)
        } finally {
            ctx.dispatchDepth--
        }
        if (real && ctx.id == 0) {
            when (name) {
                "q" -> pageDepth++
                "Q" -> if (pageDepth > 0) pageDepth--
                "cm" -> if (pageDepth == 0) baseCtm = currentCtm()
                "gs" -> if (pageDepth == 0) baseNeedsReset = currentStateNeedsReset()
            }
        }
    }

    override fun showForm(form: PDFormXObject) {
        enterForm(form) { super.showForm(form) }
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) {
        enterForm(form) { super.showTransparencyGroup(form) }
    }

    private inline fun enterForm(form: PDFormXObject, body: () -> Unit) {
        val parent = stack.last()
        // Resources in effect inside the form: its own, or (legacy PDFs) the invoking stream's.
        val res = form.resources ?: parent.resources
        val ctx = StreamContext(contexts.size, parent.id, parent.opIndex, form.cosObject, res)
        contexts.add(ctx)
        stack.add(ctx)
        val clip = formClip(form)
        if (clip != null) clips.add(clip)
        try {
            body()
        } finally {
            if (clip != null) clips.removeAt(clips.size - 1)
            stack.removeAt(stack.size - 1)
        }
    }

    /**
     * A form XObject clips everything it draws to its `/BBox`. Text outside it is in the file
     * but never visible (typical for a page that was embedded and cropped), so it must not be
     * selectable. Returns the box as a quadrilateral in display space.
     */
    private fun formClip(form: PDFormXObject): FloatArray? = try {
        val bbox = form.bBox
        if (bbox == null) null else {
            val total = form.matrix.multiply(graphicsState.currentTransformationMatrix)
            val xs = floatArrayOf(bbox.lowerLeftX, bbox.upperRightX, bbox.upperRightX, bbox.lowerLeftX)
            val ys = floatArrayOf(bbox.lowerLeftY, bbox.lowerLeftY, bbox.upperRightY, bbox.upperRightY)
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val p = total.transformPoint(xs[i], ys[i])
                out[i * 2] = u2d.x(p.x.toDouble(), p.y.toDouble()).toFloat()
                out[i * 2 + 1] = u2d.y(p.x.toDouble(), p.y.toDouble()).toFloat()
            }
            out
        }
    } catch (_: Exception) {
        null
    }

    private val clips = ArrayList<FloatArray>()

    /** True when the point lies outside any enclosing form's bounding box. */
    private fun isClipped(x: Float, y: Float): Boolean {
        for (quad in clips) {
            var sign = 0
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val ex = quad[j * 2] - quad[i * 2]
                val ey = quad[j * 2 + 1] - quad[i * 2 + 1]
                val length = sqrt((ex * ex + ey * ey).toDouble()).toFloat()
                if (length < 1e-4f) return true // a box without area shows nothing
                // Signed distance of the point from this edge.
                val distance = (ex * (y - quad[i * 2 + 1]) - ey * (x - quad[i * 2])) / length
                // A little tolerance: text often sits exactly on the edge of its box.
                if (distance > CLIP_TOLERANCE || distance < -CLIP_TOLERANCE) {
                    val sgn = if (distance > 0f) 1 else -1
                    if (sign == 0) sign = sgn else if (sign != sgn) return true
                }
            }
        }
        return false
    }

    // --- glyphs -------------------------------------------------------------------------------

    private var glyphOrdinal = 0
    private var ordinalOwnerCtx = -1
    private var ordinalOwnerOp = -1

    /**
     * Same algorithm as the base class, with per-glyph bookkeeping added. Kept as a full
     * re-implementation (rather than calling super and hooking showGlyph) because the code
     * length and pen advance of each glyph are needed and are not passed to the hooks.
     */
    override fun showText(string: ByteArray) {
        val ctx = stack.last()
        if (ordinalOwnerCtx != ctx.id || ordinalOwnerOp != ctx.opIndex) {
            ordinalOwnerCtx = ctx.id
            ordinalOwnerOp = ctx.opIndex
            glyphOrdinal = 0
        }

        val state = graphicsState
        val textState = state.textState
        var font: PDFont? = textState.font
        if (font == null) font = PDType1Font.HELVETICA
        font!!
        ctx.fontByOp[ctx.opIndex] = font

        val fontSize = textState.fontSize
        val horizontalScaling = textState.horizontalScaling / 100f
        val charSpacing = textState.characterSpacing
        val rise = textState.rise
        val renderMode = textState.renderingMode.intValue()
        val vertical = font.isVertical

        val parameters = Matrix(fontSize * horizontalScaling, 0f, 0f, fontSize, 0f, rise)
        val ctm = state.currentTransformationMatrix

        var color = 0
        var colorResolved = false

        val input = ByteArrayInputStream(string)
        while (input.available() > 0) {
            val before = input.available()
            val code = font.readCode(input)
            val codeLength = before - input.available()

            var wordSpacing = 0f
            if (codeLength == 1 && code == 32) wordSpacing += textState.wordSpacing

            val textMatrix = textMatrix
            val trm = parameters.multiply(textMatrix).multiply(ctm)
            if (vertical) trm.translate(font.getPositionVector(code))

            val w = font.getDisplacement(code)

            val tx: Float
            val ty: Float
            val penText: Float // pen movement in unscaled text space units
            if (vertical) {
                penText = w.y * fontSize + charSpacing + wordSpacing
                tx = 0f
                ty = penText
            } else {
                penText = w.x * fontSize + charSpacing + wordSpacing
                tx = penText * horizontalScaling
                ty = 0f
            }

            if (fontSize != 0f && horizontalScaling != 0f) {
                if (!colorResolved) {
                    color = resolveColor(state, renderMode)
                    colorResolved = true
                }
                record(
                    ctx, font, code, trm, w.x, w.y, penText, fontSize, horizontalScaling,
                    vertical, color, renderMode,
                )
            }
            glyphOrdinal++

            textMatrix.concatenate(Matrix.getTranslateInstance(tx, ty))
        }
    }

    private fun record(
        ctx: StreamContext, font: PDFont, code: Int, trm: Matrix,
        wx: Float, wy: Float, penText: Float, fontSize: Float, hScale: Float,
        vertical: Boolean, color: Int, renderMode: Int,
    ) {
        // Glyph space axes in user space: (a,b) is one em along the writing direction of the
        // glyph, (c,d) one em "up".
        val a = trm.scaleX.toDouble()
        val b = trm.shearY.toDouble()
        val c = trm.shearX.toDouble()
        val d = trm.scaleY.toDouble()
        val e = trm.translateX.toDouble()
        val f = trm.translateY.toDouble()

        // … and in display space.
        val xAxisX = u2d.vx(a, b)
        val xAxisY = u2d.vy(a, b)
        val yAxisX = u2d.vx(c, d)
        val yAxisY = u2d.vy(c, d)
        val ox = u2d.x(e, f)
        val oy = u2d.y(e, f)

        val xLen = sqrt(xAxisX * xAxisX + xAxisY * xAxisY)
        val yLen = sqrt(yAxisX * yAxisX + yAxisY * yAxisY)
        if (xLen < 1e-4 || yLen < 1e-4) return // degenerate, invisible

        val dirX: Double
        val dirY: Double
        val width: Double
        val pen: Double
        val size: Double
        var asc: Double
        var desc: Double

        if (!vertical) {
            dirX = xAxisX / xLen
            dirY = xAxisY / xLen
            // Visual "up" for this baseline direction in y-down display space.
            val upX = dirY
            val upY = -dirX
            // Signed height of one em along that up vector (negative for mirrored text).
            val emUp = yAxisX * upX + yAxisY * upY
            size = abs(emUp)
            if (size < 1e-3) return
            width = abs(wx.toDouble()) * xLen
            // The pen moves by penText * Th in text space, and one text space unit along x spans
            // xLen / (fontSize * Th) on screen, so Th cancels out. The division is signed on
            // purpose: with a negative font size the baseline direction is reversed too.
            pen = penText.toDouble() / fontSize.toDouble() * xLen
            val (fa, fd) = fontExtents(font)
            asc = fa * size
            desc = fd * size
        } else {
            // Vertical writing: the pen moves along the glyph space y axis (downwards).
            val sign = if (wy < 0f) -1.0 else 1.0
            dirX = sign * yAxisX / yLen
            dirY = sign * yAxisY / yLen
            size = xLen
            width = abs(wy.toDouble()) * yLen
            pen = abs(penText.toDouble()) * (yLen / abs(fontSize.toDouble()))
            asc = 0.5 * size
            desc = -0.5 * size
        }

        val unicode = unicodeFor(font, code)
        val mapped = unicode != null
        val text = cleanText(unicode ?: "\uFFFD")

        val space = try {
            val sw = font.spaceWidth
            if (sw > 0f && !sw.isNaN()) sw / 1000.0 * (if (vertical) size else xLen) else 0.0
        } catch (_: Exception) {
            0.0
        }

        // TJ number that reproduces this glyph's pen movement: tx = -n/1000 * fontSize (* Th).
        val tj = -penText * 1000f / fontSize

        // Visible only if its middle lies within the bounding boxes of all enclosing forms.
        val clipped = if (clips.isEmpty()) false else {
            val midAlong = width / 2.0
            val midUp = (asc + desc) / 2.0
            val cx = ox + dirX * midAlong + dirY * midUp
            val cy = oy + dirY * midAlong - dirX * midUp
            isClipped(cx.toFloat(), cy.toFloat())
        }

        glyphs.add(
            Glyph(
                text = text,
                ox = ox.toFloat(), oy = oy.toFloat(),
                dirX = dirX.toFloat(), dirY = dirY.toFloat(),
                width = width.toFloat(),
                penAdvance = pen.toFloat(),
                size = size.toFloat(),
                asc = asc.toFloat(), desc = desc.toFloat(),
                spaceWidth = space.toFloat(),
                font = font,
                color = color,
                renderMode = renderMode,
                mapped = mapped,
                vertical = vertical,
                ctx = ctx.id,
                op = ctx.opIndex,
                ord = glyphOrdinal,
                tjAdjust = tj,
                streamIndex = streamCounter++,
                clipped = clipped,
                fromEdit = ctx.inEdit,
            ),
        )
    }

    private val extentCache = HashMap<PDFont, Pair<Double, Double>>()

    /** Ascent/descent of the font in em units, sanitised: many PDFs carry nonsense values. */
    private fun fontExtents(font: PDFont): Pair<Double, Double> = extentCache.getOrPut(font) {
        var asc = Double.NaN
        var desc = Double.NaN
        try {
            if (font !is PDType3Font) {
                val fd = font.fontDescriptor
                if (fd != null) {
                    asc = fd.ascent / 1000.0
                    desc = fd.descent / 1000.0
                    if (desc > 0) desc = -desc // some producers write a positive descent
                }
            }
        } catch (_: Exception) {
        }
        if (asc.isNaN() || asc < 0.5 || asc > 1.1) asc = DEFAULT_ASCENT
        if (desc.isNaN() || desc > -0.08 || desc < -0.4) desc = DEFAULT_DESCENT
        Pair(asc, desc)
    }

    private fun unicodeFor(font: PDFont, code: Int): String? {
        var unicode: String? = try {
            font.toUnicode(code, glyphList())
        } catch (_: Exception) {
            null
        }
        if (unicode == null || unicode.isEmpty()) {
            // Same fallback PDFBox's own text extraction uses: for simple (single-byte) fonts the
            // code is very often the character itself; composite fonts give no such hint.
            unicode = if (font is PDSimpleFont && code in 0x20..0xFF && code != 0x7F) {
                String(charArrayOf(code.toChar()))
            } else {
                null
            }
        }
        return unicode
    }

    private fun resolveColor(state: PDGraphicsState, renderMode: Int): Int = try {
        val strokeOnly = renderMode == 1 || renderMode == 5
        val color = if (strokeOnly) state.strokingColor else state.nonStrokingColor
        if (color == null || color.isPattern) 0 else color.toRGB() and 0xFFFFFF
    } catch (_: Exception) {
        0
    }

    companion object {
        const val DEFAULT_ASCENT = 0.88
        const val DEFAULT_DESCENT = -0.22

        /**
         * Marked-content tag (`/LSPEdit BMC … EMC`) this app puts around the text it writes
         * when a paragraph is edited. The replacement text is added at the end of the page
         * content, so without a marker it would also come last in reading order.
         */
        const val EDIT_TAG = "LSPEdit"

        /** Slack for the form clip test, in points. */
        private const val CLIP_TOLERANCE = 0.5f

        @Volatile
        private var glyphListCache: GlyphList? = null

        /** Adobe glyph list plus PDFBox's additional names, as used by PDFTextStripper. */
        private fun glyphList(): GlyphList {
            glyphListCache?.let { return it }
            synchronized(this) {
                glyphListCache?.let { return it }
                val path = "com/tom_roush/pdfbox/resources/glyphlist/additional.txt"
                val list = try {
                    val input = if (PDFBoxResourceLoader.isReady()) {
                        PDFBoxResourceLoader.getStream(path)
                    } else {
                        GlyphList::class.java.getResourceAsStream("/$path")
                    }
                    if (input != null) input.use { GlyphList(GlyphList.getAdobeGlyphList(), it) } else GlyphList.getAdobeGlyphList()
                } catch (_: Exception) {
                    GlyphList.getAdobeGlyphList()
                }
                glyphListCache = list
                return list
            }
        }

        /**
         * Normalises the Unicode value of a glyph for use as text:
         *  - Latin ligatures (\uFB01, \uFB02, …) are expanded so searching and editing see plain letters,
         *  - a soft hyphen is a *visible* hyphen in PDF content,
         *  - non-breaking and other exotic spaces become ordinary spaces,
         *  - control characters carry no text.
         */
        fun cleanText(raw: String): String {
            var needsWork = false
            for (ch in raw) {
                if (ch < ' ' || ch == '\u007F' || ch == '\u00A0' || ch == '\u00AD' ||
                    ch in '\u2000'..'\u200F' || ch in '\u2028'..'\u202F' || ch == '\u3000' ||
                    ch in '\uFB00'..'\uFB06' || ch == '\uFEFF' || ch in '\u0080'..'\u009F'
                ) {
                    needsWork = true
                    break
                }
            }
            if (!needsWork) return raw
            val sb = StringBuilder(raw.length + 2)
            for (ch in raw) {
                when {
                    ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                    ch < ' ' || ch == '\u007F' || ch in '\u0080'..'\u009F' -> {}
                    ch == '\u00A0' || ch in '\u2000'..'\u200A' || ch == '\u202F' || ch == '\u3000' -> sb.append(' ')
                    ch == '\u00AD' -> sb.append('-')
                    ch in '\u200B'..'\u200F' || ch in '\u2028'..'\u202E' || ch == '\uFEFF' -> {}
                    ch in '\uFB00'..'\uFB06' -> sb.append(Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC))
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
