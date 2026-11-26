package io.github.legacylive88.simplepdfs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.legacylive88.simplepdfs.pdfcore.MarkupInfo
import io.github.legacylive88.simplepdfs.view.PdfPagesView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Layout, scrolling, zooming (feature 4) and tap reporting of the page view. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi") // density 1: one dp is one pixel, which keeps the numbers readable
class PdfPagesViewTest {

    private class Source(private val sizes: List<FloatArray>) : PdfPagesView.PageSource {
        val requests = ArrayList<String>()
        val pending = ArrayList<Pair<Bitmap, (Boolean) -> Unit>>()
        override val pageCount: Int get() = sizes.size
        override fun pageSize(pageIndex: Int): FloatArray = sizes[pageIndex]
        override fun render(pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float, done: (Boolean) -> Unit): PdfPagesView.Cancellable {
            requests.add("page=$pageIndex ${bitmap.width}x${bitmap.height} scale=%.3f off=%.0f,%.0f".format(scale, offsetX, offsetY))
            val entry = Pair(bitmap, done)
            pending.add(entry)
            return PdfPagesView.Cancellable { pending.remove(entry) }
        }
        override fun markups(pageIndex: Int): List<MarkupInfo> = emptyList()
        fun deliver() {
            val copy = ArrayList(pending)
            pending.clear()
            for ((_, done) in copy) done(true)
        }
    }

    private class Taps : PdfPagesView.Listener {
        val log = ArrayList<String>()
        var viewport = ""
        override fun onTap(pageIndex: Int, x: Float, y: Float) { log.add("tap $pageIndex %.0f,%.0f".format(x, y)) }
        override fun onDoubleTap(pageIndex: Int, x: Float, y: Float) { log.add("double $pageIndex %.0f,%.0f".format(x, y)) }
        override fun onViewportChanged(firstVisiblePage: Int, zoom: Float) { viewport = "$firstVisiblePage@%.2f".format(zoom) }
    }

    private lateinit var view: PdfPagesView
    private lateinit var source: Source
    private val taps = Taps()

    // 360 px wide view, 6 px margins: pages are 348 px wide. A 612 x 792 pt page is 348 x 450.35 px.
    private val pageW = 348f
    private val scale = 348f / 612f
    private val pageH = 792f * scale

    @Before
    fun setUp() {
        view = PdfPagesView(ApplicationProvider.getApplicationContext())
        view.listener = taps
        source = Source(List(5) { floatArrayOf(612f, 792f) })
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 640)
        view.setSource(source)
    }

    private fun draw() {
        val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }

    private var clock = 1000L

    private fun tapAt(x: Float, y: Float, gapBefore: Long = 1000) {
        clock += gapBefore
        val down = MotionEvent.obtain(clock, clock, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(clock, clock + 30, MotionEvent.ACTION_UP, x, y, 0)
        clock += 30
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    @Test
    fun pagesAreStackedAtFullWidthAndTheFirstOneIsVisible() {
        assertEquals(0, view.firstVisiblePage())
        assertEquals("0@1.00", taps.viewport)
        // top-left of page 0 is at the left margin, one gap (8) from the top
        val origin = view.pageToScreen(0, 0f, 0f)
        assertEquals(6f, origin[0], 0.01f)
        assertEquals(8f, origin[1], 0.01f)
        val second = view.pageToScreen(1, 0f, 0f)
        assertEquals(8f + pageH + 8f, second[1], 0.01f)
        // the bottom-right corner of the page maps to the right margin
        val corner = view.pageToScreen(0, 612f, 792f)
        assertEquals(354f, corner[0], 0.01f)
    }

    @Test
    fun visiblePagesAndTheNextOneAreRenderedAtTheWidthOfTheView() {
        draw()
        // 640 px of height show page 0 and the top of page 1; page 2 is prepared ahead so it
        // is ready when it scrolls into view. Pages 3 and 4 are not touched.
        assertEquals(
            listOf("page=0 348x450 scale=0.569 off=0,0", "page=1 348x450 scale=0.569 off=0,0", "page=2 348x450 scale=0.569 off=0,0"),
            source.requests,
        )
        draw()
        assertEquals("no duplicate requests while one is pending", 3, source.requests.size)
        source.deliver()
        draw()
        assertEquals("nor once the pictures have arrived", 3, source.requests.size)
    }

    @Test
    fun aRequestThatIsCancelledAndReplacedIsNotMistakenForItsReplacement() {
        draw()
        val firstRound = ArrayList(source.pending)
        view.invalidatePage(0) // cancels the pending request for page 0 ...
        draw()                 // ... and asks again
        assertEquals(4, source.requests.size)
        // the cancelled request reports back late, as a real renderer does
        firstRound.first { it.first.width == 348 }.second(false)
        draw()
        assertEquals("the replacement is still known to be on its way", 4, source.requests.size)
    }

    @Test
    fun screenPositionsMapBackToPagePositions() {
        val p = view.pageToScreen(1, 100f, 200f)
        val back = view.screenToPage(p[0], p[1])!!
        assertEquals(1, back[0].toInt())
        assertEquals(100f, back[1], 0.05f)
        assertEquals(200f, back[2], 0.05f)
        view.zoomIn(); idle(400)
        val q = view.pageToScreen(1, 100f, 200f)
        val again = view.screenToPage(q[0], q[1])!!
        assertEquals(1, again[0].toInt())
        assertEquals(100f, again[1], 0.05f)
        assertEquals(200f, again[2], 0.05f)
    }

    @Test
    fun zoomButtonsStepWithinLimitsAndKeepTheCentreInPlace() {
        assertTrue(view.canZoomIn && view.canZoomOut)
        val centreBefore = view.screenToPage(180f, 320f)!!
        view.zoomIn(); idle(400)
        assertEquals(1.4f, view.zoom, 0.001f)
        val centreAfter = view.screenToPage(180f, 320f)!!
        assertEquals("the middle of the screen still shows the same spot", centreBefore[1], centreAfter[1], 0.6f)
        assertEquals(centreBefore[2], centreAfter[2], 0.6f)

        repeat(10) { view.zoomIn(); idle(400) }
        assertEquals(PdfPagesView.MAX_ZOOM, view.zoom, 0.001f)
        assertFalse(view.canZoomIn)
        repeat(20) { view.zoomOut(); idle(400) }
        assertEquals(PdfPagesView.MIN_ZOOM, view.zoom, 0.001f)
        assertFalse(view.canZoomOut)
        // zoomed out, the narrower strip is centred
        assertEquals((360f - 360f * 0.5f) / 2f + 6f * 0.5f, view.pageToScreen(0, 0f, 0f)[0], 0.01f)
    }

    @Test
    fun whenZoomedInTheVisiblePartIsRenderedSharply() {
        draw(); source.deliver(); source.requests.clear()
        view.zoomIn(); idle(400)
        view.zoomIn(); idle(400)
        idle(400) // the detail patch is requested once things settle
        val detail = source.requests.filter { it.contains("scale=1.11") }
        assertTrue("a patch at the exact zoom (0.569 x 1.96) was requested: ${source.requests}", detail.isNotEmpty())
        // it is about the size of the screen, not the size of the whole magnified page
        val size = Regex("(\\d+)x(\\d+)").find(detail[0])!!
        assertTrue(size.groupValues[1].toInt() <= 360 * 3 / 2 + 2)
        assertTrue(size.groupValues[2].toInt() <= 640 * 3 / 2 + 2)
    }

    @Test
    fun aTapIsReportedInPageCoordinatesAfterTheDoubleTapWindow() {
        val p = view.pageToScreen(0, 300f, 400f)
        tapAt(p[0], p[1])
        assertTrue("not yet: it could become a double tap", taps.log.isEmpty())
        idle(400)
        assertEquals(listOf("tap 0 300,400"), taps.log)
    }

    @Test
    fun aDoubleTapIsReportedOnceAndNotAsTaps() {
        val p = view.pageToScreen(0, 300f, 400f)
        tapAt(p[0], p[1])
        tapAt(p[0], p[1], gapBefore = 100)
        idle(400)
        assertEquals(listOf("double 0 300,400"), taps.log)
    }

    @Test
    fun whileSelectingEveryTapCountsImmediatelyAndOnlyOnce() {
        view.immediateTaps = true
        val a = view.pageToScreen(0, 100f, 150f)
        tapAt(a[0], a[1])
        assertEquals("no waiting in select mode", listOf("tap 0 100,150"), taps.log)

        // The second tap ends select mode. The detector's late confirmation of that same tap
        // must not arrive as one more tap (it would clear the selection that was just made).
        val b = view.pageToScreen(0, 300f, 400f)
        tapAt(b[0], b[1])
        view.immediateTaps = false
        idle(400)
        assertEquals(listOf("tap 0 100,150", "tap 0 300,400"), taps.log)
    }

    @Test
    fun aDoubleTapWhileSelectingIsTwoTaps() {
        view.immediateTaps = true
        val p = view.pageToScreen(0, 300f, 400f)
        tapAt(p[0], p[1])
        tapAt(p[0], p[1], gapBefore = 100)
        idle(400)
        assertEquals(listOf("tap 0 300,400", "tap 0 300,400"), taps.log)
    }

    @Test
    fun tapsBetweenPagesGoToTheNearerPageAndTapsBesideAPageAreClamped() {
        val bottomOfFirst = view.pageToScreen(0, 300f, 792f)
        tapAt(bottomOfFirst[0], bottomOfFirst[1] + 3f) // in the gap, nearer page 0
        idle(400)
        tapAt(2f, view.pageToScreen(1, 0f, 50f)[1]) // in the left margin
        idle(400)
        assertEquals(listOf("tap 0 300,792", "tap 1 0,50"), taps.log)
    }

    @Test
    fun scrollingStopsAtTheEndsAndReportsThePage() {
        view.scrollToPage(3)
        assertEquals(3, view.firstVisiblePage())
        assertEquals("3@1.00", taps.viewport)
        view.scrollToPage(4)
        // the last page cannot scroll past the end of the document
        val lastBottom = view.pageToScreen(4, 0f, 792f)[1]
        assertEquals("the document ends one gap above the bottom edge", 640f - 8f, lastBottom, 0.5f)
        view.scrollToPage(0)
        assertEquals(0, view.firstVisiblePage())
        assertEquals(8f, view.pageToScreen(0, 0f, 0f)[1], 0.01f)
    }

    @Test
    fun toolbarsThatCoverThePagesMoveTheContentClearOfThem() {
        view.setContentInsets(100, 60)
        assertEquals("the first page starts below the top bar", 108f, view.pageToScreen(0, 0f, 0f)[1], 0.01f)
        view.scrollToPage(4)
        assertEquals("and the last page ends above the bottom bar", 640f - 60f - 8f, view.pageToScreen(4, 0f, 792f)[1], 0.5f)
        view.setContentInsets(0, 0)
        assertEquals(640f - 8f, view.pageToScreen(4, 0f, 792f)[1], 0.5f)
    }

    @Test
    fun anEditedPageIsRenderedAgainWhileTheOldPictureStaysUp() {
        draw(); source.deliver(); source.requests.clear()
        draw()
        assertTrue(source.requests.isEmpty())
        view.invalidatePage(0)
        draw()
        assertEquals(listOf("page=0 348x450 scale=0.569 off=0,0"), source.requests)
    }

    @Test
    fun newPageSizesKeepTheReaderOnTheSamePage() {
        view.scrollToPage(2)
        val mixed = Source(listOf(floatArrayOf(612f, 792f), floatArrayOf(792f, 612f), floatArrayOf(612f, 792f), floatArrayOf(612f, 792f), floatArrayOf(612f, 792f)))
        // same source object is required for a relayout; emulate lazily measured sizes instead
        val sizes = ArrayList(List(5) { floatArrayOf(612f, 792f) })
        val lazy = object : PdfPagesView.PageSource {
            override val pageCount = 5
            override fun pageSize(pageIndex: Int) = sizes[pageIndex]
            override fun render(pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float, done: (Boolean) -> Unit) = PdfPagesView.Cancellable { }
            override fun markups(pageIndex: Int) = emptyList<MarkupInfo>()
        }
        assertNotNull(mixed)
        view.setSource(lazy)
        view.scrollToPage(2)
        assertEquals(2, view.firstVisiblePage())
        sizes[1] = floatArrayOf(792f, 612f) // page 2 of the document turns out to be landscape
        view.onPageSizesChanged()
        assertEquals("still on the same page although the one above it shrank", 2, view.firstVisiblePage())
    }

    /** Drags a finger vertically by [dy] pixels (negative: the pages move up), slowly, so nothing flings. */
    private fun dragBy(dy: Float) {
        clock += 1000
        val start = clock
        fun send(action: Int, y: Float) {
            val e = MotionEvent.obtain(start, clock, action, 180f, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 500f)
        for (i in 1..10) {
            clock += 40
            send(MotionEvent.ACTION_MOVE, 500f + dy * i / 10)
        }
        clock += 500 // the finger rests before it lifts
        send(MotionEvent.ACTION_MOVE, 500f + dy)
        clock += 20
        send(MotionEvent.ACTION_UP, 500f + dy)
        idle(500)
    }

    /** Lays the view out again at a new size, as turning the phone does. */
    private fun resize(width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test
    fun turningThePhoneKeepsTheTopOfThePageExactlyWhereItWas() {
        // The bars cover 100 px at the top in portrait and 80 px in landscape.
        view.setContentInsets(100, 60)
        view.scrollToPage(2)
        draw()
        assertEquals("page 3 sits one gap below the top bar", 108f, view.pageToScreen(2, 0f, 0f)[1], 0.01f)

        // The system lays out the page view and the bars in one pass; either may come first.
        resize(640, 360)
        view.setContentInsets(80, 50)
        assertEquals(2, view.firstVisiblePage())
        assertEquals("still one gap below the (now lower) bar", 88f, view.pageToScreen(2, 0f, 0f)[1], 0.01f)
        draw()

        // and back, this time with the bars resized before the page view
        view.setContentInsets(100, 60)
        resize(360, 640)
        assertEquals(2, view.firstVisiblePage())
        assertEquals(108f, view.pageToScreen(2, 0f, 0f)[1], 0.01f)
    }

    @Test
    fun turningThePhoneInTheMiddleOfAPageKeepsTheSameLineAtTheTop() {
        view.setContentInsets(100, 60)
        view.scrollToPage(1)
        // read on: a quarter of the way into page 2
        val quarter = 792f / 4f
        val down = view.pageToScreen(1, 0f, quarter)[1] - 100f
        dragBy(-down)
        draw()
        assertEquals(100f, view.pageToScreen(1, 0f, quarter)[1], 1f)

        resize(640, 360)
        view.setContentInsets(80, 50)
        assertEquals(1, view.firstVisiblePage())
        assertEquals("the same line is at the top, under the new bar", 80f, view.pageToScreen(1, 0f, quarter)[1], 1f)
    }

    @Test
    fun barsThatOnlyHideOrShowDoNotMoveThePages() {
        view.setContentInsets(100, 60)
        view.scrollToPage(2)
        draw()
        val before = view.pageToScreen(2, 0f, 0f)[1]
        view.setContentInsets(24, 0) // tools hidden: only the status bar is left
        assertEquals("nothing jumps under the reader's eye", before, view.pageToScreen(2, 0f, 0f)[1], 0.01f)
        view.setContentInsets(100, 60)
        assertEquals(before, view.pageToScreen(2, 0f, 0f)[1], 0.01f)
    }

    @Test
    fun withoutADocumentNothingHappens() {
        view.setSource(null)
        assertEquals(-1, view.firstVisiblePage())
        assertNull(view.screenToPage(100f, 100f))
        draw()
        tapAt(100f, 100f); idle(400)
        assertTrue(taps.log.isEmpty())
        assertEquals(SystemClock.uptimeMillis() >= 0, true)
    }
}
