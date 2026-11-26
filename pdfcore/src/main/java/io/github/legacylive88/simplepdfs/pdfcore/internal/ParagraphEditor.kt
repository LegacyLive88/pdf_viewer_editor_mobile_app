package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import io.github.legacylive88.simplepdfs.pdfcore.PdfEditException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Replaces the text of one paragraph.
 *
 * This is a real edit, not a cover-up: the paragraph's glyphs are deleted from the page's
 * content streams and the new text is typeset in their place. Concretely:
 *
 *  1. every text-showing operator (`Tj`, `TJ`, `'`, `"`) that drew part of the paragraph is
 *     rewritten so the removed glyphs are replaced by an equivalent pen movement – everything
 *     else drawn by the same operator, or after it, stays exactly where it was;
 *  2. if the text lives inside a form XObject, that form (and any enclosing forms) is copied
 *     first, so other pages that share it are not affected;
 *  3. the new text is wrapped to the paragraph's width, in a bundled font that is embedded as
 *     a subset, and appended to the page content in default user space.
 */
internal class ParagraphEditor(
    private val doc: PDDocument,
    private val page: PDPage,
    private val pt: PageText,
    private val fonts: FontBook,
) {
    class Outcome(
        @JvmField val fontScale: Float,
        @JvmField val overflowed: Boolean,
        @JvmField val lineCount: Int,
        /** Number of operators in the page stream that precede the newly added text block. */
        @JvmField val originalOperatorCount: Int,
    )

    /** How the paragraph was set, measured before anything is changed. */
    class Plan(
        @JvmField val frame: Frame,
        @JvmField val style: FontBook.Style,
        @JvmField val color: Int,
        @JvmField val fontSize: Float,
        @JvmField val leading: Float,
        @JvmField val alignment: Alignment,
        /** Left edge of the first line and of the following lines (LEFT / JUSTIFIED). */
        @JvmField val firstLeft: Float,
        @JvmField val bodyLeft: Float,
        /** Right edge text may run to. */
        @JvmField val right: Float,
        /** Centre axis (CENTER) and the half width available around it. */
        @JvmField val centre: Float,
        @JvmField val halfWidth: Float,
        /** Left limit for RIGHT aligned text. */
        @JvmField val leftLimit: Float,
        @JvmField val firstBaseline: Float,
        /** Lowest position (frame v) the descenders of the last line may reach. */
        @JvmField val bottomLimit: Float,
        @JvmField val descent: Float,
    )

    // ---------------------------------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------------------------------

    fun plan(p: TextParagraph): Plan {
        val lines = p.lines
        val frame = p.frame
        val first = lines[0]

        // Dominant font, colour and size, weighted by how much text uses them.
        val fontWeight = HashMap<PDFont, Float>()
        val colorWeight = HashMap<Int, Float>()
        var descent = 0f
        for (line in lines) for (g in line.glyphs) {
            if (g.duplicate || g.text.isBlank()) continue
            val wgt = max(g.width, 0.01f)
            fontWeight[g.font] = (fontWeight[g.font] ?: 0f) + wgt
            colorWeight[g.color] = (colorWeight[g.color] ?: 0f) + wgt
            descent = min(descent, g.desc)
        }
        val font = fontWeight.maxByOrNull { it.value }?.key ?: first.glyphs[0].font
        val color = colorWeight.maxByOrNull { it.value }?.key ?: 0
        val sizes = lines.map { it.size }.sorted()
        val fontSize = sizes[sizes.size / 2]
        if (descent == 0f) descent = -0.22f * fontSize

        val leading = if (lines.size >= 2) {
            val gaps = ArrayList<Float>()
            for (i in 1 until lines.size) gaps.add(lines[i].v - lines[i - 1].v)
            gaps.sort()
            gaps[gaps.size / 2]
        } else {
            1.2f * fontSize
        }

        // Everything else on the page that is written in the same direction.
        val others = pt.layout.lines.filter { it.bucket == p.bucket && it.paragraph != p.index && it.words.isNotEmpty() }
        val sameDirection = pt.layout.lines.filter { it.bucket == p.bucket && it.words.isNotEmpty() }
        val pageLeft = sameDirection.minOf { it.u0 }
        val pageRight = sameDirection.maxOf { it.u1 }

        val paraLeft = p.left
        val paraRight = p.right
        val paraTop = p.top
        val paraBottom = p.bottom

        val alignment = detectAlignment(p, pageLeft, pageRight, fontSize, sameDirection.size)

        // Nearest text to the right on the same rows limits how far a line may grow.
        var obstacleRight = Float.MAX_VALUE
        for (o in others) {
            val vOverlap = min(o.bottom, paraBottom) - max(o.top, paraTop)
            if (vOverlap > 0.2f * min(o.size, fontSize) && o.u0 >= paraRight - 0.1f * fontSize) {
                obstacleRight = min(obstacleRight, o.u0)
            }
        }
        var obstacleLeft = -Float.MAX_VALUE
        for (o in others) {
            val vOverlap = min(o.bottom, paraBottom) - max(o.top, paraTop)
            if (vOverlap > 0.2f * min(o.size, fontSize) && o.u1 <= paraLeft + 0.1f * fontSize) {
                obstacleLeft = max(obstacleLeft, o.u1)
            }
        }
        val gapKeep = 0.6f * fontSize

        var right = paraRight
        var leftLimit = paraLeft
        var centre = (paraLeft + paraRight) / 2f
        var halfWidth = (paraRight - paraLeft) / 2f
        when (alignment) {
            Alignment.LEFT, Alignment.JUSTIFIED -> {
                // A multi-line paragraph shows its own wrap width. A single line does not, so let
                // it run as far as the text column goes.
                if (lines.size == 1) right = max(paraRight, min(pageRight, obstacleRight - gapKeep))
            }
            Alignment.RIGHT -> {
                leftLimit = if (lines.size == 1) min(paraLeft, max(pageLeft, obstacleLeft + gapKeep)) else paraLeft
            }
            Alignment.CENTER -> {
                centre = if (lines.size == 1) first.centre else lines.map { it.centre }.sorted()[lines.size / 2]
                val roomRight = min(pageRight, obstacleRight - gapKeep) - centre
                val roomLeft = centre - max(pageLeft, obstacleLeft + gapKeep)
                halfWidth = max(halfWidth, min(roomLeft, roomRight))
            }
        }

        val bodyLeft = if (lines.size >= 2) lines.subList(1, lines.size).minOf { it.u0 } else first.u0
        val firstLeft = first.u0

        // Room below: the next text underneath, or the bottom page margin.
        val corners = arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(pt.geom.displayWidth.toFloat(), 0f),
            floatArrayOf(0f, pt.geom.displayHeight.toFloat()),
            floatArrayOf(pt.geom.displayWidth.toFloat(), pt.geom.displayHeight.toFloat()),
        )
        var bottomLimit = corners.maxOf { frame.v(it[0], it[1]) } - PAGE_MARGIN
        val spanLeft = min(paraLeft, leftLimit)
        val spanRight = max(paraRight, right)
        for (o in others) {
            if (o.top < paraBottom - 0.3f * fontSize) continue
            val uOverlap = min(o.u1, spanRight) - max(o.u0, spanLeft)
            if (uOverlap > 0f) bottomLimit = min(bottomLimit, o.top - 0.15f * fontSize)
        }
        // Never less room than the paragraph already occupies.
        bottomLimit = max(bottomLimit, paraBottom)

        return Plan(
            frame = frame,
            style = FontBook.styleFor(font),
            color = color,
            fontSize = fontSize,
            leading = leading,
            alignment = alignment,
            firstLeft = firstLeft,
            bodyLeft = bodyLeft,
            right = right,
            centre = centre,
            halfWidth = halfWidth,
            leftLimit = leftLimit,
            firstBaseline = first.v,
            bottomLimit = bottomLimit,
            descent = descent,
        )
    }

    private fun detectAlignment(
        p: TextParagraph, pageLeft: Float, pageRight: Float, size: Float, linesOnPage: Int,
    ): Alignment {
        val lines = p.lines
        val n = lines.size
        if (n == 1) {
            val line = lines[0]
            if (linesOnPage < 4) return Alignment.LEFT
            val pageWidth = pageRight - pageLeft
            val fromLeft = line.u0 - pageLeft
            val fromRight = pageRight - line.u1
            if (fromLeft > 2f * size && fromRight > 2f * size && abs(fromLeft - fromRight) <= 1.5f * size) {
                return Alignment.CENTER
            }
            if (fromRight <= 0.5f * size && fromLeft > 0.25f * pageWidth) return Alignment.RIGHT
            return Alignment.LEFT
        }
        fun spread(values: List<Float>): Float = if (values.isEmpty()) 0f else values.max() - values.min()
        val tol = 0.4f * size
        val bodyLefts = lines.subList(1, n).map { it.u0 }
        val leftAligned = spread(bodyLefts) <= tol
        if (leftAligned) {
            if (n >= 3 && spread(lines.subList(0, n - 1).map { it.u1 }) <= tol) return Alignment.JUSTIFIED
            return Alignment.LEFT
        }
        if (spread(lines.map { it.u1 }) <= tol) return Alignment.RIGHT
        if (spread(lines.map { it.centre }) <= 1.2f * tol) return Alignment.CENTER
        return Alignment.LEFT
    }

    // ---------------------------------------------------------------------------------------------
    // Applying
    // ---------------------------------------------------------------------------------------------

    private class ResourceEditor(base: COSDictionary?) {
        val dict: COSDictionary = if (base != null) COSDictionary(base) else COSDictionary()
        private val cloned = HashMap<COSName, COSDictionary>()
        var modified = false
            private set

        init {
            dict.isDirect = true
        }

        fun sub(kind: COSName): COSDictionary = cloned.getOrPut(kind) {
            val existing = dict.getDictionaryObject(kind) as? COSDictionary
            val copy = if (existing != null) COSDictionary(existing) else COSDictionary()
            copy.isDirect = true
            dict.setItem(kind, copy)
            modified = true
            copy
        }

        fun add(kind: COSName, prefix: String, value: COSBase): COSName {
            val target = sub(kind)
            var i = 1
            var name: COSName
            do {
                name = COSName.getPDFName(prefix + i)
                i++
            } while (target.containsKey(name))
            target.setItem(name, value)
            modified = true
            return name
        }
    }

    private val removals = HashMap<Int, HashMap<Int, HashMap<Int, Float>>>()
    private val needsRewrite = HashSet<Int>()
    private val childByParentOp = HashMap<Long, Int>()

    private fun key(ctx: Int, op: Int): Long = (ctx.toLong() shl 32) or (op.toLong() and 0xFFFFFFFFL)

    /**
     * Applies the edit to the in-memory document. Throws without having changed anything only
     * for unsupported characters; any later failure leaves the document half modified and the
     * caller must discard it (the session reloads from the last saved file).
     */
    fun replace(p: TextParagraph, rawText: String): Outcome {
        val plan = plan(p)
        val text = TextLayout.sanitize(rawText).trim('\n', ' ')
        val missing = fonts.unsupported(plan.style, text)
        if (missing.isNotEmpty()) {
            throw PdfEditException(
                PdfEditException.Reason.UNSUPPORTED_CHARACTERS,
                "The built-in fonts cannot draw: $missing",
            )
        }

        // 1. Which glyphs go.
        for (line in p.lines) for (g in line.glyphs) {
            removals.getOrPut(g.ctx) { HashMap() }.getOrPut(g.op) { HashMap() }[g.ord] = g.tjAdjust
        }
        for (c in pt.contexts) if (c.parent >= 0) childByParentOp[key(c.parent, c.parentOp)] = c.id
        for (ctxId in removals.keys) {
            var c = ctxId
            while (c >= 0 && needsRewrite.add(c)) c = pt.contexts[c].parent
        }
        needsRewrite.add(0)

        // 2. Rewrite the page stream (and, through it, any nested forms).
        val pageResources = ResourceEditor(page.resources?.cosObject)
        val parser = PDFStreamParser(page)
        parser.parse()
        val state = TokenState()
        val out = dropEmptyEditBlocks(rewriteTokens(0, parser.tokens, pageResources, state))
        val originalOperatorCount = out.count { it is Operator }

        // 3. Close whatever the original content left open, so the new text starts clean.
        repeat(state.depth) { out.add(op("Q")) }
        if (state.inText) out.add(op("ET"))

        // 4. Typeset the new text.
        val typeset = if (text.isEmpty()) null else typeset(plan, text, pageResources, out)

        // 5. Drop fonts added by earlier edits that are no longer referenced.
        pruneUnusedFonts(pageResources, out)

        // 6. Install the new content and resources.
        val stream = doc.document.createCOSStream()
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.cosObject.setItem(COSName.CONTENTS, stream)
        if (pageResources.modified) page.setResources(PDResources(pageResources.dict))

        return Outcome(
            fontScale = typeset?.scale ?: 1f,
            overflowed = typeset?.overflowed ?: false,
            lineCount = typeset?.lineCount ?: 0,
            originalOperatorCount = originalOperatorCount,
        )
    }

    private class TokenState {
        var depth = 0
        var inText = false
        var operatorCount = 0
    }

    private fun op(name: String): Operator = Operator.getOperator(name)

    private fun rewriteTokens(
        ctxId: Int, tokens: List<Any>, resources: ResourceEditor, state: TokenState?,
    ): ArrayList<Any> {
        val ctx = pt.contexts[ctxId]
        val out = ArrayList<Any>(tokens.size + 64)
        var opIndex = -1
        var operandStart = 0
        val ctxRemovals = removals[ctxId]

        for (tok in tokens) {
            if (tok !is Operator) {
                out.add(tok)
                continue
            }
            opIndex++
            val name = tok.name
            if (state != null) {
                when (name) {
                    "q" -> state.depth++
                    "Q" -> if (state.depth > 0) state.depth--
                    "BT" -> state.inText = true
                    "ET" -> state.inText = false
                }
            }

            val remove = ctxRemovals?.get(opIndex)
            val child = if (name == "Do") childByParentOp[key(ctxId, opIndex)] else null
            if (remove != null && (name == "Tj" || name == "TJ" || name == "'" || name == "\"")) {
                val operands = ArrayList<Any>(out.subList(operandStart, out.size))
                while (out.size > operandStart) out.removeAt(out.size - 1)
                val font = ctx.fontByOp[opIndex]
                    ?: throw PdfEditException(PdfEditException.Reason.FAILED, "Lost track of a text operator")
                out.addAll(rewriteTextOperator(name, operands, remove, font, tok))
            } else if (child != null && child in needsRewrite) {
                val newForm = rewriteForm(child)
                val newName = resources.add(COSName.XOBJECT, XOBJECT_PREFIX, newForm)
                // The operand of Do is the name just before the operator.
                if (out.size > operandStart) out[operandStart] = newName
                out.add(tok)
            } else {
                out.add(tok)
            }
            operandStart = out.size
        }
        state?.operatorCount = opIndex + 1
        return out
    }

    /** Builds a private copy of a form XObject with the paragraph's glyphs removed. */
    private fun rewriteForm(ctxId: Int): COSStream {
        val ctx = pt.contexts[ctxId]
        val old = ctx.stream ?: throw PdfEditException(PdfEditException.Reason.FAILED, "Form stream missing")
        val parser = PDFStreamParser(old.createInputStream().use { it.readBytes() })
        parser.parse()
        val resources = ResourceEditor(ctx.resources?.cosObject)
        val tokens = rewriteTokens(ctxId, parser.tokens, resources, null)

        val copy = doc.document.createCOSStream()
        for ((k, v) in old.entrySet()) {
            if (k == COSName.LENGTH || k == COSName.FILTER || k == COSName.DECODE_PARMS ||
                k == COSName.F || k == COSName.F_FILTER || k == COSName.F_DECODE_PARMS || k == COSName.DL
            ) continue
            copy.setItem(k, v)
        }
        if (resources.modified) copy.setItem(COSName.RESOURCES, resources.dict)
        copy.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        return copy
    }

    /**
     * Rewrites one text-showing operator so that the glyphs in [remove] are no longer drawn but
     * the pen ends up exactly where it did before.
     */
    private fun rewriteTextOperator(
        name: String, operands: List<Any>, remove: Map<Int, Float>, font: PDFont, original: Operator,
    ): List<Any> {
        val result = ArrayList<Any>()
        val elements: List<COSBase>
        when (name) {
            "TJ" -> {
                val arr = operands.firstOrNull() as? COSArray ?: return operands + original
                elements = (0 until arr.size()).map { arr.getObject(it) }
            }
            "Tj" -> {
                val s = operands.firstOrNull() as? COSString ?: return operands + original
                elements = listOf(s)
            }
            "'" -> {
                val s = operands.firstOrNull() as? COSString ?: return operands + original
                result.add(op("T*"))
                elements = listOf(s)
            }
            else -> { // "
                if (operands.size < 3) return operands + original
                val s = operands[2] as? COSString ?: return operands + original
                result.add(operands[0]); result.add(op("Tw"))
                result.add(operands[1]); result.add(op("Tc"))
                result.add(op("T*"))
                elements = listOf(s)
            }
        }

        val array = COSArray()
        val pendingBytes = ByteArrayOutputStream()
        var pendingAdjust = 0f
        var ordinal = 0

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                array.add(COSString(pendingBytes.toByteArray()))
                pendingBytes.reset()
            }
        }

        fun flushAdjust() {
            if (pendingAdjust != 0f) {
                array.add(COSFloat(pendingAdjust))
                pendingAdjust = 0f
            }
        }

        for (el in elements) {
            when (el) {
                is COSNumber -> {
                    flushBytes()
                    pendingAdjust += el.floatValue()
                }
                is COSString -> {
                    val bytes = el.bytes
                    val input = ByteArrayInputStream(bytes)
                    while (input.available() > 0) {
                        val before = input.available()
                        font.readCode(input)
                        val length = before - input.available()
                        val start = bytes.size - before
                        val adjust = remove[ordinal]
                        if (adjust != null) {
                            flushBytes()
                            pendingAdjust += adjust
                        } else {
                            flushAdjust()
                            pendingBytes.write(bytes, start, length)
                        }
                        ordinal++
                    }
                }
                else -> {} // the interpreter ignores anything else inside a TJ array as well
            }
        }
        flushBytes()
        flushAdjust()
        if (array.size() > 0) {
            result.add(array)
            result.add(op("TJ"))
        }
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Typesetting the replacement text
    // ---------------------------------------------------------------------------------------------

    private class Typeset(val scale: Float, val overflowed: Boolean, val lineCount: Int)

    /** A font being embedded for this edit, plus per-character advance widths at size 1. */
    private inner class EmbeddedFont(val asset: String) {
        var pdFont: PDType0Font? = null
        var resourceName: COSName? = null
        fun font(): PDType0Font = pdFont ?: fonts.open(asset).use { PDType0Font.load(doc, it, true) }.also { pdFont = it }
    }

    private fun typeset(plan: Plan, text: String, resources: ResourceEditor, out: ArrayList<Any>): Typeset {
        val primary = EmbeddedFont(plan.style.asset)
        val fallback = EmbeddedFont(FontBook.FALLBACK_ASSET)
        fun fontFor(cp: Int): EmbeddedFont =
            if (cp == ' '.code || fonts.hasGlyph(primary.asset, cp)) primary else fallback

        val widthCache = HashMap<Int, Float>()
        val measure: (Int) -> Float = { cp ->
            widthCache.getOrPut(cp) {
                try {
                    fontFor(cp).font().getStringWidth(String(Character.toChars(cp))) / 1000f
                } catch (_: Exception) {
                    0.5f
                }
            }
        }

        // Find the largest size (down to MIN_SCALE) at which the text fits the room available.
        var scale = 1f
        var lines: List<TextLayout.Line>
        var overflowed = false
        while (true) {
            val size = plan.fontSize * scale
            lines = wrapFor(plan, text, size, measure)
            val lastBaseline = plan.firstBaseline + (lines.size - 1) * plan.leading * scale
            val bottom = lastBaseline - plan.descent * scale
            if (bottom <= plan.bottomLimit + 0.5f) break
            if (scale <= MIN_SCALE + 1e-3f) {
                overflowed = true
                break
            }
            scale = max(MIN_SCALE, scale - 0.05f)
        }
        val size = plan.fontSize * scale
        val leading = plan.leading * scale

        // Tag the block as ours so it can be given back its place in reading order when read.
        out.add(COSName.getPDFName(GlyphExtractor.EDIT_TAG))
        out.add(op("BMC"))
        // Graphics state: back to default user space, default transparency, then the text.
        out.add(op("q"))
        val base = pt.endState.baseCtm
        if (!base.isIdentity()) {
            val inv = base.inverse()
            for (v in doubleArrayOf(inv.a, inv.b, inv.c, inv.d, inv.e, inv.f)) out.add(COSFloat(v.toFloat()))
            out.add(op("cm"))
        }
        if (pt.endState.needsGraphicsStateReset) {
            val gs = COSDictionary()
            gs.setItem(COSName.TYPE, COSName.EXT_G_STATE)
            gs.setItem(COSName.getPDFName("ca"), COSFloat(1f))
            gs.setItem(COSName.getPDFName("CA"), COSFloat(1f))
            gs.setItem(COSName.getPDFName("BM"), COSName.getPDFName("Normal"))
            gs.setItem(COSName.getPDFName("SMask"), COSName.getPDFName("None"))
            gs.isDirect = true
            out.add(resources.add(COSName.EXT_G_STATE, GSTATE_PREFIX, gs))
            out.add(op("gs"))
        }
        out.add(op("BT"))
        out.add(COSInteger.ZERO); out.add(op("Tc"))
        out.add(COSInteger.ZERO); out.add(op("Tw"))
        out.add(COSInteger.get(100)); out.add(op("Tz"))
        out.add(COSInteger.ZERO); out.add(op("Ts"))
        out.add(COSInteger.ZERO); out.add(op("Tr"))
        out.add(COSFloat(((plan.color shr 16) and 0xFF) / 255f))
        out.add(COSFloat(((plan.color shr 8) and 0xFF) / 255f))
        out.add(COSFloat((plan.color and 0xFF) / 255f))
        out.add(op("rg"))

        // Text axes in user space: along the baseline, and "up" from it.
        val d2u = pt.geom.displayToUser
        val f = plan.frame
        val ax = d2u.vx(f.dirX.toDouble(), f.dirY.toDouble())
        val ay = d2u.vy(f.dirX.toDouble(), f.dirY.toDouble())
        val bx = d2u.vx(f.dirY.toDouble(), -f.dirX.toDouble())
        val by = d2u.vy(f.dirY.toDouble(), -f.dirX.toDouble())

        var current: EmbeddedFont? = null
        for ((index, line) in lines.withIndex()) {
            if (line.words.isEmpty()) continue
            val natural = line.width * size
            val avail = lineWidth(plan, index)
            val start = when (plan.alignment) {
                Alignment.LEFT, Alignment.JUSTIFIED -> if (index == 0) plan.firstLeft else plan.bodyLeft
                Alignment.RIGHT -> plan.right - natural
                Alignment.CENTER -> plan.centre - natural / 2f
            }
            val v = plan.firstBaseline + index * leading
            val dx = f.x(start, v).toDouble()
            val dy = f.y(start, v).toDouble()
            out.add(COSFloat(ax.toFloat())); out.add(COSFloat(ay.toFloat()))
            out.add(COSFloat(bx.toFloat())); out.add(COSFloat(by.toFloat()))
            out.add(COSFloat(d2u.x(dx, dy).toFloat())); out.add(COSFloat(d2u.y(dx, dy).toFloat()))
            out.add(op("Tm"))

            // Justified text: share the slack between the word spaces (never on a last line, and
            // not when it would tear the line apart).
            var extraPerSpace = 0f
            if (plan.alignment == Alignment.JUSTIFIED && !line.last && line.spaces > 0) {
                val slack = avail - natural
                val each = slack / line.spaces
                if (each > 0f && each <= MAX_JUSTIFY_STRETCH * size) extraPerSpace = each
            }
            val adjustment = -extraPerSpace * 1000f / size

            val s = line.text
            var array = COSArray()
            val run = StringBuilder()
            var runFont: EmbeddedFont? = null

            fun flushRun() {
                if (run.isEmpty()) return
                val ef = runFont!!
                val pd = ef.font()
                var i = 0
                while (i < run.length) {
                    val cp = run.codePointAt(i)
                    pd.addToSubset(cp)
                    i += Character.charCount(cp)
                }
                array.add(COSString(pd.encode(run.toString())))
                run.setLength(0)
            }

            fun flushArray() {
                flushRun()
                if (array.size() > 0) {
                    out.add(array)
                    out.add(op("TJ"))
                    array = COSArray()
                }
            }

            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                val ef = fontFor(cp)
                if (ef !== runFont) {
                    flushArray()
                    runFont = ef
                    if (ef !== current) {
                        if (ef.resourceName == null) {
                            ef.resourceName = resources.add(COSName.FONT, FONT_PREFIX, ef.font().cosObject)
                        }
                        out.add(ef.resourceName!!)
                        out.add(COSFloat(size))
                        out.add(op("Tf"))
                        current = ef
                    }
                }
                run.appendCodePoint(cp)
                if (cp == ' '.code && adjustment != 0f) {
                    flushRun()
                    array.add(COSFloat(adjustment))
                }
            }
            flushArray()
        }
        out.add(op("ET"))
        out.add(op("Q"))
        out.add(op("EMC"))

        // Build the subset font programs now that every character is known.
        for (ef in arrayOf(primary, fallback)) {
            val pd = ef.pdFont ?: continue
            if (ef.resourceName != null) pd.subset()
        }
        return Typeset(scale, overflowed, lines.size)
    }

    private fun lineWidth(plan: Plan, index: Int): Float = when (plan.alignment) {
        Alignment.LEFT, Alignment.JUSTIFIED -> plan.right - (if (index == 0) plan.firstLeft else plan.bodyLeft)
        Alignment.RIGHT -> plan.right - plan.leftLimit
        Alignment.CENTER -> 2f * plan.halfWidth
    }

    private fun wrapFor(plan: Plan, text: String, size: Float, measure: (Int) -> Float): List<TextLayout.Line> {
        // A hair of tolerance so text that fitted before the edit still fits after rounding.
        val slack = 0.02f * size
        val first = max(lineWidth(plan, 0) + slack, size) / size
        val other = max(lineWidth(plan, 1) + slack, size) / size
        return TextLayout.wrap(text, first, other, measure)
    }

    /**
     * Removes blocks written by earlier edits (`/LSPEdit BMC … EMC`) that no longer draw any
     * text because their paragraph has just been edited again. Without this every re-edit would
     * leave an empty shell, and its font, behind.
     */
    private fun dropEmptyEditBlocks(tokens: ArrayList<Any>): ArrayList<Any> {
        val out = ArrayList<Any>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val next = tokens.getOrNull(i + 1)
            if (tok is COSName && tok.name == GlyphExtractor.EDIT_TAG && next is Operator && next.name == "BMC") {
                // find the matching EMC
                var depth = 0
                var end = -1
                var drawsText = false
                var j = i + 1
                while (j < tokens.size) {
                    val t = tokens[j]
                    if (t is Operator) {
                        when (t.name) {
                            "BMC", "BDC" -> depth++
                            "EMC" -> {
                                depth--
                                if (depth == 0) end = j
                            }
                            // An operator whose glyphs were all removed is left as pure pen
                            // movement (`[-1234] TJ`); only actual strings draw something.
                            "Tj", "TJ", "'", "\"" -> if (showsGlyphs(tokens[j - 1])) drawsText = true
                        }
                    }
                    if (end >= 0) break
                    j++
                }
                if (end >= 0 && !drawsText) {
                    i = end + 1
                    continue
                }
            }
            out.add(tok)
            i++
        }
        return out
    }

    private fun showsGlyphs(operand: Any?): Boolean = when (operand) {
        is COSString -> operand.bytes.isNotEmpty()
        is COSArray -> (0 until operand.size()).any { (operand.getObject(it) as? COSString)?.bytes?.isNotEmpty() == true }
        else -> false
    }

    /** Removes `/LSPFn` font resources that no `Tf` in the page stream refers to any more. */
    private fun pruneUnusedFonts(resources: ResourceEditor, tokens: List<Any>) {
        val existing = resources.dict.getDictionaryObject(COSName.FONT) as? COSDictionary ?: return
        val ours = existing.keySet().filter { it.name.startsWith(FONT_PREFIX) }
        if (ours.isEmpty()) return
        val used = HashSet<COSName>()
        var prev: Any? = null
        var prev2: Any? = null
        for (tok in tokens) {
            if (tok is Operator && tok.name == "Tf" && prev2 is COSName) used.add(prev2)
            prev2 = prev
            prev = tok
        }
        val stale = ours.filter { it !in used }
        if (stale.isEmpty()) return
        val fontsDict = resources.sub(COSName.FONT)
        for (name in stale) fontsDict.removeItem(name)
    }

    companion object {
        const val FONT_PREFIX = "LSPF"
        const val XOBJECT_PREFIX = "LSPX"
        const val GSTATE_PREFIX = "LSPG"

        /** Smallest factor the font may be scaled by to make longer text fit. */
        const val MIN_SCALE = 0.6f

        /** Largest extra gap per word space when justifying, in em. */
        const val MAX_JUSTIFY_STRETCH = 0.6f

        /** Distance kept from the bottom page edge when a paragraph grows, in points. */
        const val PAGE_MARGIN = 24f
    }
}
