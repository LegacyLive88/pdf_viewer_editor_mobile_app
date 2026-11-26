package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import io.github.legacylive88.simplepdfs.pdfcore.AnnotationKind
import io.github.legacylive88.simplepdfs.pdfcore.MarkupInfo
import io.github.legacylive88.simplepdfs.pdfcore.MarkupKind
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Reads and writes text-markup annotations (`/Highlight` and friends).
 *
 * Highlights are stored as standard PDF annotations with an appearance stream, so they show up
 * in every other PDF viewer and can be removed again by any PDF editor.
 */
internal object Markup {

    const val DEFAULT_HIGHLIGHT_RGB = 0xFFEB3B

    private val QUADPOINTS: COSName = COSName.getPDFName("QuadPoints")
    private val POPUP: COSName = COSName.getPDFName("Popup")
    private val NM: COSName = COSName.getPDFName("NM")
    private val CA: COSName = COSName.getPDFName("CA")
    private val CA_NS: COSName = COSName.getPDFName("ca")
    private val AIS: COSName = COSName.getPDFName("AIS")
    private val BM: COSName = COSName.getPDFName("BM")
    private val FORMTYPE: COSName = COSName.getPDFName("FormType")
    private val CREATION_DATE: COSName = COSName.getPDFName("CreationDate")
    private val GS_NAME: COSName = COSName.getPDFName("GS")
    private val IRT: COSName = COSName.getPDFName("IRT")

    private fun kindOf(dict: COSDictionary): MarkupKind? = when (dict.getNameAsString(COSName.SUBTYPE)) {
        "Highlight" -> MarkupKind.HIGHLIGHT
        "Underline" -> MarkupKind.UNDERLINE
        "StrikeOut" -> MarkupKind.STRIKEOUT
        "Squiggly" -> MarkupKind.SQUIGGLY
        else -> null
    }

    private fun annotations(page: PDPage): COSArray? = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray

    /**
     * Adds to [into] the kinds of annotation on the page that put something on it other than
     * text markup: form fields, notes, stamps, drawings… Links and pop-ups draw nothing, and
     * hidden or empty annotations cannot be seen anyway.
     */
    fun otherAnnotationKinds(page: PDPage, into: MutableSet<AnnotationKind>) {
        val array = annotations(page) ?: return
        for (i in 0 until array.size()) {
            val dict = array.getObject(i) as? COSDictionary ?: continue
            if (kindOf(dict) != null) continue
            val kind = when (dict.getNameAsString(COSName.SUBTYPE)) {
                null, "Link", "Popup" -> continue
                "Widget" -> AnnotationKind.FORM_FIELD
                "Text", "FreeText" -> AnnotationKind.NOTE
                "Stamp" -> AnnotationKind.STAMP
                "Ink", "Line", "Square", "Circle", "Polygon", "PolyLine" -> AnnotationKind.DRAWING
                else -> AnnotationKind.OTHER
            }
            if (kind in into) continue
            val flags = dict.getInt(COSName.F, 0)
            if (flags and 2 != 0 || flags and 32 != 0) continue // Hidden / NoView
            val rect = dict.getDictionaryObject(COSName.RECT) as? COSArray
            if (rect != null && rect.size() >= 4) {
                val v = FloatArray(4) { k -> (rect.getObject(k) as? COSNumber)?.floatValue() ?: 0f }
                if (v[0] == v[2] || v[1] == v[3]) continue // no area, e.g. an invisible signature field
            }
            into.add(kind)
        }
    }

    // --- reading ------------------------------------------------------------------------------

    fun read(page: PDPage, pageIndex: Int, geom: PageGeometry): List<MarkupInfo> {
        val array = annotations(page) ?: return emptyList()
        val out = ArrayList<MarkupInfo>()
        for (i in 0 until array.size()) {
            val dict = array.getObject(i) as? COSDictionary ?: continue
            val kind = kindOf(dict) ?: continue
            val flags = dict.getInt(COSName.F, 0)
            if (flags and 2 != 0 || flags and 32 != 0) continue // Hidden / NoView
            val quads = displayQuads(dict, geom)
            if (quads.isEmpty()) continue
            out.add(MarkupInfo(pageIndex, kind, colorOf(dict, kind), opacityOf(dict), quads))
        }
        return out
    }

    private fun opacityOf(dict: COSDictionary): Float {
        val ca = dict.getDictionaryObject(CA) as? COSNumber ?: return 1f
        return ca.floatValue().coerceIn(0f, 1f)
    }

    private fun colorOf(dict: COSDictionary, kind: MarkupKind): Int {
        val c = dict.getDictionaryObject(COSName.C) as? COSArray
        val v = FloatArray(c?.size() ?: 0) { i -> ((c!!.getObject(i) as? COSNumber)?.floatValue() ?: 0f).coerceIn(0f, 1f) }
        fun pack(r: Float, g: Float, b: Float): Int =
            ((r * 255f + 0.5f).toInt() shl 16) or ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
        return when (v.size) {
            1 -> pack(v[0], v[0], v[0])
            3 -> pack(v[0], v[1], v[2])
            4 -> pack((1 - v[0]) * (1 - v[3]), (1 - v[1]) * (1 - v[3]), (1 - v[2]) * (1 - v[3]))
            else -> if (kind == MarkupKind.HIGHLIGHT) 0xFFFF00 else 0x000000
        }
    }

    /** The annotation's quads in display space; falls back to `/Rect` when there are none. */
    private fun displayQuads(dict: COSDictionary, geom: PageGeometry): List<Quad> {
        val out = ArrayList<Quad>()
        val u2d = geom.userToDisplay
        val qp = dict.getDictionaryObject(QUADPOINTS) as? COSArray
        if (qp != null && qp.size() >= 8) {
            val n = qp.size() / 8
            for (q in 0 until n) {
                val p = DoubleArray(8)
                var ok = true
                for (k in 0 until 8) {
                    val num = qp.getObject(q * 8 + k) as? COSNumber
                    if (num == null) {
                        ok = false
                        break
                    }
                    p[k] = num.floatValue().toDouble()
                }
                if (!ok) continue
                val xs = FloatArray(4) { u2d.x(p[it * 2], p[it * 2 + 1]).toFloat() }
                val ys = FloatArray(4) { u2d.y(p[it * 2], p[it * 2 + 1]).toFloat() }
                out.add(orderedQuad(xs, ys))
            }
        }
        if (out.isEmpty()) {
            val rect = dict.getDictionaryObject(COSName.RECT) as? COSArray
            if (rect != null && rect.size() >= 4) {
                val r = DoubleArray(4) { (rect.getObject(it) as? COSNumber)?.floatValue()?.toDouble() ?: 0.0 }
                val px = doubleArrayOf(r[0], r[2], r[2], r[0])
                val py = doubleArrayOf(r[3], r[3], r[1], r[1])
                val xs = FloatArray(4) { u2d.x(px[it], py[it]).toFloat() }
                val ys = FloatArray(4) { u2d.y(px[it], py[it]).toFloat() }
                out.add(simplePolygon(xs, ys))
            }
        }
        return out
    }

    /**
     * QuadPoints are written top-left, top-right, bottom-left, bottom-right by nearly all
     * software, although the specification's wording suggests going round the box. Accept both.
     */
    private fun orderedQuad(xs: FloatArray, ys: FloatArray): Quad {
        val z = Quad(xs[0], ys[0], xs[1], ys[1], xs[3], ys[3], xs[2], ys[2])
        if (isSimple(z)) return z
        val ring = Quad(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3])
        if (isSimple(ring)) return ring
        return simplePolygon(xs, ys)
    }

    private fun isSimple(q: Quad): Boolean {
        val xs = floatArrayOf(q.x1, q.x2, q.x3, q.x4)
        val ys = floatArrayOf(q.y1, q.y2, q.y3, q.y4)
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val cross = (xs[j] - xs[i]) * (ys[k] - ys[j]) - (ys[j] - ys[i]) * (xs[k] - xs[j])
            if (cross != 0f) {
                val s = if (cross > 0f) 1 else -1
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return sign != 0
    }

    /** Orders four arbitrary points around their centre so they form a proper polygon. */
    private fun simplePolygon(xs: FloatArray, ys: FloatArray): Quad {
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        val idx = (0 until 4).sortedBy { kotlin.math.atan2((ys[it] - cy).toDouble(), (xs[it] - cx).toDouble()) }
        return Quad(xs[idx[0]], ys[idx[0]], xs[idx[1]], ys[idx[1]], xs[idx[2]], ys[idx[2]], xs[idx[3]], ys[idx[3]])
    }

    // --- writing ------------------------------------------------------------------------------

    /**
     * Adds a highlight annotation covering [quads] (display space) and returns its dictionary.
     * The page's `/Annots` array is replaced by a new direct array so that an incremental save
     * only needs to rewrite the page dictionary.
     */
    fun addHighlight(
        doc: PDDocument, page: PDPage, geom: PageGeometry,
        quads: List<Quad>, colorRgb: Int, contents: String?,
    ): COSDictionary {
        val annot = COSDictionary()
        annot.setItem(COSName.TYPE, COSName.ANNOT)
        annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Highlight"))
        annot.setItem(COSName.F, COSInteger.get(4)) // Print
        annot.setItem(COSName.C, colorArray(colorRgb))
        annot.setItem(CA, COSFloat(1f))
        val now = pdfDate()
        annot.setItem(COSName.M, COSString(now))
        annot.setItem(CREATION_DATE, COSString(now))
        annot.setItem(NM, COSString(UUID.randomUUID().toString()))
        if (!contents.isNullOrEmpty()) {
            annot.setItem(COSName.CONTENTS, COSString(if (contents.length > 2000) contents.substring(0, 2000) else contents))
        }
        annot.setItem(COSName.P, page.cosObject)
        applyQuads(doc, annot, geom, quads)

        val old = annotations(page)
        val fresh = COSArray()
        if (old != null) for (i in 0 until old.size()) fresh.add(old.get(i))
        fresh.add(annot)
        page.cosObject.setItem(COSName.ANNOTS, fresh)
        return annot
    }

    /**
     * Removes highlighting from the area covered by [selection] (display space). Highlights that
     * only partly overlap are trimmed, not deleted. Other markup kinds are left alone.
     *
     * @return dictionaries that were modified in place and must be part of the next save;
     *   null if nothing overlapped
     */
    fun removeHighlights(doc: PDDocument, page: PDPage, geom: PageGeometry, selection: List<Quad>): Set<COSDictionary>? {
        val old = annotations(page) ?: return null
        val modified = LinkedHashSet<COSDictionary>()
        val removed = HashSet<COSDictionary>()
        var changed = false

        for (i in 0 until old.size()) {
            val dict = old.getObject(i) as? COSDictionary ?: continue
            if (kindOf(dict) != MarkupKind.HIGHLIGHT) continue
            val current = displayQuads(dict, geom)
            if (current.isEmpty()) continue
            val remaining = subtract(current, selection) ?: continue
            changed = true
            if (remaining.isEmpty()) {
                removed.add(dict)
                (dict.getDictionaryObject(POPUP) as? COSDictionary)?.let { removed.add(it) }
            } else {
                applyQuads(doc, dict, geom, remaining)
                dict.setItem(COSName.M, COSString(pdfDate()))
                modified.add(dict)
            }
        }
        if (!changed) return null

        if (removed.isNotEmpty()) {
            val fresh = COSArray()
            for (i in 0 until old.size()) {
                val obj = old.getObject(i)
                if (obj is COSDictionary) {
                    if (obj in removed) continue
                    // A reply to a removed annotation makes no sense on its own.
                    val irt = obj.getDictionaryObject(IRT)
                    if (irt is COSDictionary && irt in removed) continue
                }
                fresh.add(old.get(i))
            }
            page.cosObject.setItem(COSName.ANNOTS, fresh)
        }
        return modified
    }

    /**
     * Cuts [selection] out of [quads]. Returns null when nothing overlaps, otherwise what is left.
     * Works in the text-aligned frame of each selection box, so it is correct for rotated text.
     */
    fun subtract(quads: List<Quad>, selection: List<Quad>): List<Quad>? {
        var pieces: List<Quad> = quads
        var touched = false
        for (sel in selection) {
            val frame = Frame.of(sel)
            val s = FrameBox.of(frame, sel)
            val next = ArrayList<Quad>(pieces.size + 1)
            for (piece in pieces) {
                val a = FrameBox.of(frame, piece)
                val vOverlap = min(a.v1, s.v1) - max(a.v0, s.v0)
                val uOverlap = min(a.u1, s.u1) - max(a.u0, s.u0)
                val sameLine = vOverlap >= 0.5f * min(a.height, s.height)
                if (!sameLine || uOverlap <= MIN_PIECE) {
                    next.add(piece)
                    continue
                }
                touched = true
                // Highlights made elsewhere are usually padded a little beyond the glyphs. What
                // is left over after removing whole words is then a sliver thinner than any
                // character; keeping it would leave a stray mark behind.
                val sliver = max(MIN_PIECE, SLIVER_RATIO * a.height)
                if (s.u0 - a.u0 > sliver) next.add(frame.quad(a.u0, s.u0, a.v0, a.v1))
                if (a.u1 - s.u1 > sliver) next.add(frame.quad(s.u1, a.u1, a.v0, a.v1))
            }
            pieces = next
        }
        return if (touched) pieces else null
    }

    /** Fragments narrower than this (points) are not worth keeping or acting on. */
    private const val MIN_PIECE = 0.75f

    /** Leftovers narrower than this fraction of the line height are dropped. */
    private const val SLIVER_RATIO = 0.4f

    /** Writes `/QuadPoints`, `/Rect` and a matching appearance stream for [quads]. */
    private fun applyQuads(doc: PDDocument, annot: COSDictionary, geom: PageGeometry, quads: List<Quad>) {
        val d2u = geom.displayToUser
        val qp = COSArray()
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val path = StringBuilder()
        for (q in quads) {
            // display corners: 1 start-top, 2 end-top, 3 end-bottom, 4 start-bottom
            val dx = floatArrayOf(q.x1, q.x2, q.x3, q.x4)
            val dy = floatArrayOf(q.y1, q.y2, q.y3, q.y4)
            val ux = DoubleArray(4) { d2u.x(dx[it].toDouble(), dy[it].toDouble()) }
            val uy = DoubleArray(4) { d2u.y(dx[it].toDouble(), dy[it].toDouble()) }
            // QuadPoints order: top-left, top-right, bottom-left, bottom-right
            for (k in intArrayOf(0, 1, 3, 2)) {
                qp.add(COSFloat(round3(ux[k])))
                qp.add(COSFloat(round3(uy[k])))
            }
            for (k in 0 until 4) {
                minX = min(minX, ux[k]); maxX = max(maxX, ux[k])
                minY = min(minY, uy[k]); maxY = max(maxY, uy[k])
            }
            path.append(num(ux[0])).append(' ').append(num(uy[0])).append(" m ")
                .append(num(ux[1])).append(' ').append(num(uy[1])).append(" l ")
                .append(num(ux[2])).append(' ').append(num(uy[2])).append(" l ")
                .append(num(ux[3])).append(' ').append(num(uy[3])).append(" l h\n")
        }
        annot.setItem(QUADPOINTS, qp)
        val rect = COSArray()
        rect.add(COSFloat(round3(minX)))
        rect.add(COSFloat(round3(minY)))
        rect.add(COSFloat(round3(maxX)))
        rect.add(COSFloat(round3(maxY)))
        annot.setItem(COSName.RECT, rect)

        // Appearance: every box in ONE path with a single fill, so boxes of neighbouring lines
        // that overlap a little do not show up as darker bands.
        val rgb = (annot.getDictionaryObject(COSName.C) as? COSArray)
        val r = (rgb?.getObject(0) as? COSNumber)?.floatValue() ?: 1f
        val g = (rgb?.getObject(1) as? COSNumber)?.floatValue() ?: 1f
        val b = (rgb?.getObject(2) as? COSNumber)?.floatValue() ?: 0f
        val opacity = (annot.getDictionaryObject(CA) as? COSNumber)?.floatValue() ?: 1f
        val content = "/GS gs\n${num(r.toDouble())} ${num(g.toDouble())} ${num(b.toDouble())} rg\n${path}f\n"

        val stream: COSStream = doc.document.createCOSStream()
        stream.setItem(COSName.TYPE, COSName.XOBJECT)
        stream.setItem(COSName.SUBTYPE, COSName.FORM)
        stream.setItem(FORMTYPE, COSInteger.ONE)
        stream.setItem(COSName.BBOX, rect.copyNumbers())
        val gs = COSDictionary()
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE)
        gs.setItem(BM, COSName.getPDFName("Multiply"))
        gs.setItem(CA, COSFloat(opacity))
        gs.setItem(CA_NS, COSFloat(opacity))
        gs.setItem(AIS, com.tom_roush.pdfbox.cos.COSBoolean.FALSE)
        gs.isDirect = true
        val gsMap = COSDictionary()
        gsMap.setItem(GS_NAME, gs)
        gsMap.isDirect = true
        val resources = COSDictionary()
        resources.setItem(COSName.EXT_G_STATE, gsMap)
        resources.isDirect = true
        stream.setItem(COSName.RESOURCES, resources)
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }

        val ap = COSDictionary()
        ap.setItem(COSName.N, stream)
        ap.isDirect = true
        annot.setItem(COSName.AP, ap)
    }

    private fun COSArray.copyNumbers(): COSArray {
        val out = COSArray()
        for (i in 0 until size()) out.add(get(i) as COSBase)
        return out
    }

    private fun colorArray(rgb: Int): COSArray {
        val a = COSArray()
        a.add(COSFloat(round3(((rgb shr 16) and 0xFF) / 255.0)))
        a.add(COSFloat(round3(((rgb shr 8) and 0xFF) / 255.0)))
        a.add(COSFloat(round3((rgb and 0xFF) / 255.0)))
        return a
    }

    private fun round3(v: Double): Float = (Math.round(v * 1000.0) / 1000.0).toFloat()

    /** Formats a number for a content stream: fixed notation, no exponent, no trailing zeros. */
    fun num(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val s = String.format(Locale.US, "%.4f", v)
        var end = s.length
        while (end > 0 && s[end - 1] == '0') end--
        if (end > 0 && s[end - 1] == '.') end--
        val out = s.substring(0, end)
        return if (out == "-0" || out.isEmpty()) "0" else out
    }

    fun pdfDate(): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        return String.format(
            Locale.US, "D:%04d%02d%02d%02d%02d%02dZ",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
    }
}
