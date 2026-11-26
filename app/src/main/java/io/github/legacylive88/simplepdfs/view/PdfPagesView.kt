package io.github.legacylive88.simplepdfs.view

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import io.github.legacylive88.simplepdfs.pdfcore.MarkupInfo
import io.github.legacylive88.simplepdfs.pdfcore.MarkupKind
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the pages of a document as one continuous vertical strip that can be scrolled, flung
 * and zoomed, and reports taps in page coordinates.
 *
 * ## Coordinates
 * *Document space* is the strip at zoom 1: every page is as wide as the view (minus a small
 * margin) and pages are stacked with a gap. The screen shows `document * zoom - scroll`.
 * *Page space* is what the engine uses: points from the page's top-left corner.
 *
 * ## Pixels
 * Each visible page has a *base bitmap* rendered at zoom 1. When zoomed in, the visible part of
 * each page is additionally rendered at the exact zoom as a *detail patch* once the gesture
 * settles, so text is sharp at any magnification without ever holding a huge bitmap.
 */
class PdfPagesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Where pages and their overlays come from. All methods are called on the main thread. */
    interface PageSource {
        val pageCount: Int

        /** Page size in points: width, height. */
        fun pageSize(pageIndex: Int): FloatArray

        /**
         * Renders part of a page into [bitmap] off the main thread and then calls [done] on the
         * main thread. The returned handle cancels delivery.
         */
        fun render(
            pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float,
            done: (Boolean) -> Unit,
        ): Cancellable

        /** Highlights etc. on a page, in engine page space. May be empty until known. */
        fun markups(pageIndex: Int): List<MarkupInfo>

        /**
         * Factor from engine page coordinates (markups, selection) to this view's page
         * coordinates. 1 except for the rare page whose size the engine and the renderer
         * measure in different units.
         */
        fun overlayScale(pageIndex: Int): Float = 1f
    }

    fun interface Cancellable {
        fun cancel()
    }

    interface Listener {
        fun onTap(pageIndex: Int, x: Float, y: Float) {}
        fun onDoubleTap(pageIndex: Int, x: Float, y: Float) {}
        fun onViewportChanged(firstVisiblePage: Int, zoom: Float) {}
    }

    var listener: Listener? = null

    /**
     * When true every tap is reported immediately and a double tap counts as two taps. Used
     * while the user is picking the two ends of a selection.
     */
    var immediateTaps: Boolean = false

    // --- document ---------------------------------------------------------------------------------

    private var source: PageSource? = null
    private var pageCount = 0
    private var pageTop = FloatArray(0)      // document space
    private var pageHeight = FloatArray(0)   // document space
    private var pageScale = FloatArray(0)    // document pixels per point
    private var docHeight = 0f
    private val margin = dp(6f)
    private val gap = dp(8f)

    // --- viewport ---------------------------------------------------------------------------------

    var zoom = 1f
        private set
    private var scrollXf = 0f
    private var scrollYf = 0f
    private var insetTop = 0
    private var insetBottom = 0

    // --- pixels -----------------------------------------------------------------------------------

    private class Tile(val bitmap: Bitmap, val version: Int, val rect: RectF, val zoom: Float)

    /** A render that has been asked for and not yet answered. */
    private class Request {
        var handle: Cancellable? = null
        var finished = false
    }

    private val base = LinkedHashMap<Int, Tile>(16, 0.75f, true)
    private val detail = HashMap<Int, Tile>()
    private val pendingBase = HashMap<Int, Request>()
    private val pendingDetail = HashMap<Int, Request>()
    private var pageVersion = IntArray(0)
    private var baseBytes = 0L
    private val maxBaseBytes: Long = min(Runtime.getRuntime().maxMemory() / 5, 96L * 1024 * 1024)

    // --- overlays ---------------------------------------------------------------------------------

    private var selectionPage = -1
    private var selectionQuads: List<Quad> = emptyList()
    private var anchorPage = -1
    private var anchorQuads: List<Quad> = emptyList()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = 0x30000000 }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x553B82F6
    }
    private val anchorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x333B82F6
    }
    private val anchorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1D4ED8.toInt()
    }
    private val path = Path()
    private val tmpRect = RectF()
    private var backgroundColor = 0xFFE7EAEE.toInt()

    // --- gestures ---------------------------------------------------------------------------------

    private val scroller = OverScroller(context)
    private val handler = Handler(Looper.getMainLooper())
    private val detailRunnable = Runnable { requestDetail() }
    private var zoomAnimator: ValueAnimator? = null
    private var lastReportedPage = -1
    private var lastReportedZoom = 0f
    private var tapAlreadyReported = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            tapAlreadyReported = false
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // While pinching, the scale detector moves the content around the focal point.
            if (scaleGestures.isInProgress || e2.pointerCount > 1) return false
            scrollTo(scrollXf + dx, scrollYf + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleGestures.isInProgress) return false
            scroller.fling(
                scrollXf.roundToInt(), scrollYf.roundToInt(), (-vx).roundToInt(), (-vy).roundToInt(),
                minScrollX().roundToInt(), maxScrollX().roundToInt(),
                minScrollY().roundToInt(), maxScrollY().roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (immediateTaps) {
                dispatchTap(e, false)
                tapAlreadyReported = true
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // The detector confirms a tap a moment after the finger lifted. If that same tap
            // was already reported (select mode), it must not be reported a second time: by
            // now select mode may be over, and the "new" tap would clear the fresh selection.
            if (!tapAlreadyReported) dispatchTap(e, false)
            tapAlreadyReported = false
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            // Reported at the position of the second tap, when the finger lifts.
            if (e.actionMasked == MotionEvent.ACTION_UP) dispatchTap(e, !immediateTaps)
            return true
        }
    })

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply {
        // Double-tap-and-drag zooming would fight with double tap = edit paragraph.
        isQuickScaleEnabled = false
    }

    init {
        isFocusable = true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /** Sets (or clears) the document. Resets zoom and scroll position. */
    fun setSource(newSource: PageSource?) {
        cancelAll()
        recycleAll()
        source = newSource
        pageCount = newSource?.pageCount ?: 0
        pageVersion = IntArray(pageCount)
        zoom = 1f
        scrollXf = 0f
        selectionPage = -1
        anchorPage = -1
        layoutPages()
        scrollYf = minScrollY()
        lastReportedPage = -1
        invalidate()
        reportViewport()
    }

    /** Call when page sizes reported by the source have changed (they are measured lazily). */
    fun onPageSizesChanged() {
        if (source == null) return
        val anchor = topAnchor()
        layoutPages()
        restoreAnchor(anchor)
        invalidate()
    }

    /** Call when the pixels of a page have changed (the paragraph on it was edited). */
    fun invalidatePage(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        pageVersion[pageIndex]++
        pendingBase.remove(pageIndex)?.handle?.cancel()
        pendingDetail.remove(pageIndex)?.handle?.cancel()
        invalidate()
        scheduleDetail(0)
    }

    /** Call when highlights changed. */
    fun onMarkupsChanged() = invalidate()

    fun setSelection(pageIndex: Int, quads: List<Quad>) {
        selectionPage = if (quads.isEmpty()) -1 else pageIndex
        selectionQuads = quads
        invalidate()
    }

    /** Marks the first of the two points of a selection in progress. */
    fun setSelectionAnchor(pageIndex: Int, quads: List<Quad>) {
        anchorPage = if (quads.isEmpty()) -1 else pageIndex
        anchorQuads = quads
        invalidate()
    }

    /** Space at the top and bottom that is covered by toolbars: content can scroll clear of it. */
    fun setContentInsets(top: Int, bottom: Int) {
        if (top == insetTop && bottom == insetBottom) return
        val atTop = scrollYf <= minScrollY() + 1f
        insetTop = top
        insetBottom = bottom
        if (atTop) scrollYf = minScrollY()
        clampScroll()
        invalidate()
    }

    fun setPageBackgroundColor(color: Int) {
        backgroundColor = color
        invalidate()
    }

    fun zoomIn() = animateZoom(zoom * ZOOM_STEP)

    fun zoomOut() = animateZoom(zoom / ZOOM_STEP)

    val canZoomIn: Boolean get() = zoom < MAX_ZOOM - 0.001f
    val canZoomOut: Boolean get() = zoom > MIN_ZOOM + 0.001f

    /** Index of the page at the top of the visible area, or -1 without a document. */
    fun firstVisiblePage(): Int {
        if (pageCount == 0) return -1
        // the first page that still has something below the top edge of the visible area
        val y = (scrollYf + insetTop + 1f) / zoom
        for (i in 0 until pageCount) if (pageTop[i] + pageHeight[i] > y) return i
        return pageCount - 1
    }

    /** Scrolls so that the given page is at the top of the visible area. */
    fun scrollToPage(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        scroller.forceFinished(true)
        // with the usual gap above it, exactly as the first page sits when a document opens
        scrollTo(scrollXf, (pageTop[pageIndex] - gap) * zoom - insetTop)
    }

    /** Position within this view of a point given in page space. */
    fun pageToScreen(pageIndex: Int, x: Float, y: Float): FloatArray {
        val s = pageScale[pageIndex]
        return floatArrayOf((margin + x * s) * zoom - scrollXf, (pageTop[pageIndex] + y * s) * zoom - scrollYf)
    }

    // ---------------------------------------------------------------------------------------------
    // Layout and scrolling
    // ---------------------------------------------------------------------------------------------

    private fun baseWidth(): Float = max(1f, width - 2 * margin)

    private fun layoutPages() {
        val src = source
        val n = pageCount
        if (pageTop.size != n) {
            pageTop = FloatArray(n)
            pageHeight = FloatArray(n)
            pageScale = FloatArray(n)
        }
        var y = gap
        val w = baseWidth()
        for (i in 0 until n) {
            val size = src!!.pageSize(i)
            val pw = max(1f, size[0])
            val ph = max(1f, size[1])
            pageScale[i] = w / pw
            pageHeight[i] = ph * pageScale[i]
            pageTop[i] = y
            y += pageHeight[i] + gap
        }
        docHeight = y
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (source == null || w == 0) return
        val anchor = if (oldw > 0 && pageTop.isNotEmpty()) topAnchor() else null
        // Base bitmaps were rendered for the old width.
        cancelAll()
        recycleAll()
        layoutPages()
        if (anchor != null) restoreAnchor(anchor) else scrollYf = minScrollY()
        clampScroll()
        reportViewport()
    }

    /** Page and position within it (0..1) that is currently at the top of the visible area. */
    private fun topAnchor(): FloatArray {
        if (pageCount == 0 || pageTop.isEmpty()) return floatArrayOf(0f, 0f)
        val page = firstVisiblePage().coerceAtLeast(0)
        val y = (scrollYf + insetTop) / zoom
        val fraction = if (pageHeight[page] > 0f) (y - pageTop[page]) / pageHeight[page] else 0f
        return floatArrayOf(page.toFloat(), fraction)
    }

    private fun restoreAnchor(anchor: FloatArray) {
        if (pageCount == 0) return
        val page = anchor[0].toInt().coerceIn(0, pageCount - 1)
        scrollYf = (pageTop[page] + anchor[1] * pageHeight[page]) * zoom - insetTop
        clampScroll()
    }

    private fun contentWidth(): Float = width * zoom
    private fun contentHeight(): Float = docHeight * zoom

    private fun minScrollX(): Float = if (contentWidth() <= width) -(width - contentWidth()) / 2f else 0f
    private fun maxScrollX(): Float = if (contentWidth() <= width) minScrollX() else contentWidth() - width
    private fun minScrollY(): Float = -insetTop.toFloat()
    private fun maxScrollY(): Float = max(minScrollY(), contentHeight() - height + insetBottom)

    private fun clampScroll() {
        scrollXf = scrollXf.coerceIn(minScrollX(), maxScrollX())
        scrollYf = scrollYf.coerceIn(minScrollY(), maxScrollY())
    }

    private fun scrollTo(x: Float, y: Float) {
        scrollXf = x
        scrollYf = y
        clampScroll()
        invalidate()
        scheduleDetail(DETAIL_DELAY_MS)
        reportViewport()
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return
        val docX = (scrollXf + focusX) / zoom
        val docY = (scrollYf + focusY) / zoom
        zoom = z
        scrollXf = docX * zoom - focusX
        scrollYf = docY * zoom - focusY
        clampScroll()
        invalidate()
        scheduleDetail(DETAIL_DELAY_MS)
        reportViewport()
    }

    private fun animateZoom(target: Float) {
        val to = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomAnimator?.cancel()
        if (to == zoom || width == 0) return
        scroller.forceFinished(true)
        val fx = width / 2f
        val fy = (insetTop + (height - insetBottom)) / 2f
        zoomAnimator = ValueAnimator.ofFloat(zoom, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomTo(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat()
            scrollYf = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
            scheduleDetail(DETAIL_DELAY_MS)
            reportViewport()
        }
    }

    private fun reportViewport() {
        val page = firstVisiblePage()
        if (page != lastReportedPage || zoom != lastReportedZoom) {
            lastReportedPage = page
            lastReportedZoom = zoom
            listener?.onViewportChanged(page, zoom)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    // performClick() is called where a tap is recognised (dispatchTap), which lint cannot see
    // because the gesture detectors sit in between.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        // Both detectors always see the whole gesture so neither is left in a half-finished state.
        scaleGestures.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun dispatchTap(e: MotionEvent, double: Boolean) {
        val hit = screenToPage(e.x, e.y) ?: return
        val page = hit[0].toInt()
        if (double) {
            listener?.onDoubleTap(page, hit[1], hit[2])
        } else {
            performClick()
            listener?.onTap(page, hit[1], hit[2])
        }
    }

    /**
     * Taps are reported with their position through [Listener]; this only lets the system and
     * accessibility services know that the view was activated.
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Converts a screen position to (page, x, y) in page space; null when not on a page. */
    fun screenToPage(sx: Float, sy: Float): FloatArray? {
        if (pageCount == 0) return null
        val docX = (sx + scrollXf) / zoom
        val docY = (sy + scrollYf) / zoom
        for (i in 0 until pageCount) {
            // The gap between two pages belongs half to each.
            if (docY >= pageTop[i] - gap / 2 && docY < pageTop[i] + pageHeight[i] + gap / 2) {
                val size = source!!.pageSize(i)
                val x = ((docX - margin) / pageScale[i]).coerceIn(0f, size[0])
                val y = ((docY - pageTop[i]) / pageScale[i]).coerceIn(0f, size[1])
                return floatArrayOf(i.toFloat(), x, y)
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val src = source ?: return
        if (pageCount == 0 || width == 0) return

        val top = scrollYf / zoom
        val bottom = (scrollYf + height) / zoom
        canvas.withTranslation(-scrollXf, -scrollYf) {
            canvas.scale(zoom, zoom)

            val w = baseWidth()
            val hairline = 1f / zoom
            for (i in 0 until pageCount) {
                val pt = pageTop[i]
                val pb = pt + pageHeight[i]
                if (pb < top) continue
                if (pt > bottom) break

                tmpRect.set(margin, pt, margin + w, pb)
                canvas.drawRect(tmpRect.left - hairline, tmpRect.top - hairline, tmpRect.right + hairline, tmpRect.bottom + 2 * hairline, shadowPaint)

                val tile = base[i]
                if (tile != null && !tile.bitmap.isRecycled) {
                    canvas.drawBitmap(tile.bitmap, null, tmpRect, bitmapPaint)
                } else {
                    canvas.drawRect(tmpRect, pagePaint)
                }
                if (tile == null || tile.version != pageVersion[i]) requestBase(i)

                val patch = detail[i]
                if (patch != null && !patch.bitmap.isRecycled && patch.version == pageVersion[i] && patch.zoom == zoom) {
                    canvas.drawBitmap(patch.bitmap, null, patch.rect, bitmapPaint)
                }

                drawOverlays(canvas, src, i)
            }
        }

        // Have the pages just outside the screen ready before they scroll into view.
        val first = firstVisiblePage()
        val last = lastVisiblePage()
        for (i in intArrayOf(last + 1, first - 1)) {
            if (i in 0 until pageCount) {
                val tile = base[i]
                if (tile == null || tile.version != pageVersion[i]) requestBase(i)
            }
        }
    }

    private fun drawOverlays(canvas: Canvas, src: PageSource, page: Int) {
        val s = pageScale[page] * src.overlayScale(page)
        val ox = margin
        val oy = pageTop[page]

        for (m in src.markups(page)) {
            when (m.kind) {
                MarkupKind.HIGHLIGHT -> {
                    // One path with one fill, so overlapping line boxes do not show as bands.
                    path.rewind()
                    for (q in m.quads) addQuad(path, q, ox, oy, s)
                    highlightPaint.color = lighten(m.colorRgb, m.opacity)
                    canvas.drawPath(path, highlightPaint)
                }
                else -> {
                    linePaint.color = 0xFF000000.toInt() or m.colorRgb
                    for (q in m.quads) {
                        val thickness = max(0.6f, 0.06f * distance(q.x1, q.y1, q.x4, q.y4)) * s
                        linePaint.strokeWidth = thickness
                        // Along the bottom edge for underlines, through the middle for strike-outs.
                        val t = if (m.kind == MarkupKind.STRIKEOUT) 0.5f else 0.92f
                        val ax = q.x1 + (q.x4 - q.x1) * t
                        val ay = q.y1 + (q.y4 - q.y1) * t
                        val bx = q.x2 + (q.x3 - q.x2) * t
                        val by = q.y2 + (q.y3 - q.y2) * t
                        canvas.drawLine(ox + ax * s, oy + ay * s, ox + bx * s, oy + by * s, linePaint)
                    }
                }
            }
        }

        if (page == anchorPage && anchorQuads.isNotEmpty()) {
            path.rewind()
            for (q in anchorQuads) addQuad(path, q, ox, oy, s)
            canvas.drawPath(path, anchorFill)
            anchorStroke.strokeWidth = dp(1.5f) / zoom
            canvas.drawPath(path, anchorStroke)
        }
        if (page == selectionPage && selectionQuads.isNotEmpty()) {
            path.rewind()
            for (q in selectionQuads) addQuad(path, q, ox, oy, s)
            canvas.drawPath(path, selectionPaint)
        }
    }

    private fun addQuad(p: Path, q: Quad, ox: Float, oy: Float, s: Float) {
        p.moveTo(ox + q.x1 * s, oy + q.y1 * s)
        p.lineTo(ox + q.x2 * s, oy + q.y2 * s)
        p.lineTo(ox + q.x3 * s, oy + q.y3 * s)
        p.lineTo(ox + q.x4 * s, oy + q.y4 * s)
        p.close()
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Colour to multiply the page with. Opacity is folded into the colour (towards white)
     * instead of using alpha, because a multiplying paint with alpha would also multiply the
     * alpha of the pixels underneath.
     */
    private fun lighten(rgb: Int, opacity: Float): Int {
        val o = opacity.coerceIn(0f, 1f)
        fun channel(c: Int) = (255 - o * (255 - c)).roundToInt().coerceIn(0, 255)
        return Color.rgb(channel((rgb shr 16) and 0xFF), channel((rgb shr 8) and 0xFF), channel(rgb and 0xFF))
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering requests
    // ---------------------------------------------------------------------------------------------

    private fun requestBase(page: Int) {
        val src = source ?: return
        if (pendingBase.containsKey(page) || width == 0) return
        val w = baseWidth()
        var bw = w.roundToInt().coerceAtLeast(1)
        var bh = pageHeight[page].roundToInt().coerceAtLeast(1)
        var scale = pageScale[page]
        // Extremely tall pages: stay within a sane bitmap size.
        val pixels = bw.toLong() * bh
        if (pixels > MAX_BASE_PIXELS) {
            val k = kotlin.math.sqrt(MAX_BASE_PIXELS.toDouble() / pixels).toFloat()
            bw = (bw * k).roundToInt().coerceAtLeast(1)
            bh = (bh * k).roundToInt().coerceAtLeast(1)
            scale *= k
        }
        val bitmap = try {
            createBitmap(bw, bh)
        } catch (_: OutOfMemoryError) {
            trimBase(0)
            return
        }
        val version = pageVersion[page]
        val requestedFor = src
        val request = Request()
        request.handle = src.render(page, bitmap, scale, 0f, 0f) { ok ->
            request.finished = true
            // Only forget the request if it is still the current one for this page: a cancelled
            // request reports back too, possibly after its replacement has been registered.
            if (pendingBase[page] === request) pendingBase.remove(page)
            if (!ok || source !== requestedFor || page >= pageCount || version != pageVersion[page]) {
                bitmap.recycle()
                if (ok && source === requestedFor) invalidate()
            } else {
                putBase(page, Tile(bitmap, version, RectF(), 1f))
                invalidate()
            }
        }
        // The source may answer before render() even returns.
        if (!request.finished) pendingBase[page] = request
    }

    private fun putBase(page: Int, tile: Tile) {
        base.remove(page)?.let {
            baseBytes -= it.bitmap.byteCount
            it.bitmap.recycle()
        }
        base[page] = tile
        baseBytes += tile.bitmap.byteCount
        trimBase(maxBaseBytes)
    }

    /** Evicts least recently drawn pages until the cache fits, never the visible ones. */
    private fun trimBase(limit: Long) {
        if (baseBytes <= limit) return
        val first = firstVisiblePage()
        val keepFrom = first - 1
        val keepTo = lastVisiblePage() + 1
        val it = base.entries.iterator()
        while (baseBytes > limit && it.hasNext()) {
            val e = it.next()
            if (e.key in keepFrom..keepTo) continue
            baseBytes -= e.value.bitmap.byteCount
            e.value.bitmap.recycle()
            it.remove()
        }
    }

    private fun lastVisiblePage(): Int {
        if (pageCount == 0) return -1
        val y = (scrollYf + height) / zoom
        var last = 0
        for (i in 0 until pageCount) if (pageTop[i] <= y) last = i else break
        return last
    }

    private fun scheduleDetail(delay: Long) {
        handler.removeCallbacks(detailRunnable)
        handler.postDelayed(detailRunnable, delay)
    }

    /** Renders the visible part of each visible page at the current zoom. */
    private fun requestDetail() {
        val src = source ?: return
        if (width == 0 || pageCount == 0) return
        // Drop patches for pages that scrolled away or were made for another zoom.
        val first = firstVisiblePage()
        val last = lastVisiblePage()
        val it = detail.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in first..last || e.value.zoom != zoom || e.value.version != pageVersion[e.key]) {
                e.value.bitmap.recycle()
                it.remove()
            }
        }
        if (zoom <= DETAIL_FROM_ZOOM) {
            if (detail.isNotEmpty()) invalidate()
            return
        }
        if (!scroller.isFinished || scaleGestures.isInProgress || zoomAnimator?.isRunning == true) {
            scheduleDetail(DETAIL_DELAY_MS)
            return
        }

        // Visible area in document space, grown a little so small pans stay sharp.
        val growX = width * 0.25f / zoom
        val growY = height * 0.25f / zoom
        val viewLeft = scrollXf / zoom - growX
        val viewTop = scrollYf / zoom - growY
        val viewRight = (scrollXf + width) / zoom + growX
        val viewBottom = (scrollYf + height) / zoom + growY
        val w = baseWidth()

        for (i in first..last) {
            if (i !in 0 until pageCount) continue
            val left = max(margin, viewLeft)
            val top = max(pageTop[i], viewTop)
            val right = min(margin + w, viewRight)
            val bottom = min(pageTop[i] + pageHeight[i], viewBottom)
            if (right - left < 1f || bottom - top < 1f) continue

            val existing = detail[i]
            if (existing != null && existing.rect.left <= left + 1f && existing.rect.top <= top + 1f &&
                existing.rect.right >= right - 1f && existing.rect.bottom >= bottom - 1f
            ) continue

            pendingDetail.remove(i)?.handle?.cancel()
            val bw = ((right - left) * zoom).roundToInt().coerceAtLeast(1)
            val bh = ((bottom - top) * zoom).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                createBitmap(bw, bh)
            } catch (_: OutOfMemoryError) {
                continue
            }
            val rect = RectF(left, top, left + bw / zoom, top + bh / zoom)
            val z = zoom
            val version = pageVersion[i]
            val requestedFor = src
            // pixels per point at this zoom, and where the patch starts within the page, in pixels
            val scale = pageScale[i] * z
            val offsetX = (left - margin) * z
            val offsetY = (top - pageTop[i]) * z
            val request = Request()
            request.handle = src.render(i, bitmap, scale, offsetX, offsetY) { ok ->
                request.finished = true
                if (pendingDetail[i] === request) pendingDetail.remove(i)
                if (!ok || source !== requestedFor || i >= pageCount || version != pageVersion[i] || z != zoom) {
                    bitmap.recycle()
                } else {
                    detail.remove(i)?.bitmap?.recycle()
                    detail[i] = Tile(bitmap, version, rect, z)
                    invalidate()
                }
            }
            if (!request.finished) pendingDetail[i] = request
        }
    }

    private fun cancelAll() {
        handler.removeCallbacks(detailRunnable)
        // Cancelling makes the source report back, which edits these maps: iterate over copies.
        val running = ArrayList(pendingBase.values) + ArrayList(pendingDetail.values)
        pendingBase.clear()
        pendingDetail.clear()
        for (r in running) r.handle?.cancel()
    }

    private fun recycleAll() {
        for (t in base.values) t.bitmap.recycle()
        for (t in detail.values) t.bitmap.recycle()
        base.clear()
        detail.clear()
        baseBytes = 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
    }

    /** Frees every bitmap; call when the hosting screen goes away for good. */
    fun release() {
        cancelAll()
        recycleAll()
        source = null
        pageCount = 0
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
        const val ZOOM_STEP = 1.4f

        /** Above this zoom the base bitmap is visibly soft and a detail patch is rendered. */
        private const val DETAIL_FROM_ZOOM = 1.05f
        private const val DETAIL_DELAY_MS = 140L
        private const val MAX_BASE_PIXELS = 12_000_000L
    }
}
