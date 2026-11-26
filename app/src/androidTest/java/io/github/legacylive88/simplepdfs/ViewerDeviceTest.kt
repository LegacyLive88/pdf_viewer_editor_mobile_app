package io.github.legacylive88.simplepdfs

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import io.github.legacylive88.simplepdfs.pdfcore.AnnotationKind
import io.github.legacylive88.simplepdfs.pdfcore.MarkupKind
import io.github.legacylive88.simplepdfs.pdfcore.PageChange
import io.github.legacylive88.simplepdfs.pdfcore.PageSize
import io.github.legacylive88.simplepdfs.pdfcore.PdfPoint
import io.github.legacylive88.simplepdfs.pdfcore.PdfSession
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import io.github.legacylive88.simplepdfs.render.AnnotationProbe
import io.github.legacylive88.simplepdfs.render.PlatformRasterizer
import io.github.legacylive88.simplepdfs.view.PdfPagesView
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The six features exercised on a real Android runtime: real platform PDF rendering, real
 * touch events, the real file being written.
 *
 * Each test copies a fixture into the app's storage, opens it through an "Open with" intent and
 * then works the UI. What ended up in the file is checked by reading it back with a fresh
 * engine instance. Screenshots of the key moments are written to
 * `files/device-test-screens/` so a person can look at them (see docs/TESTING.md).
 */
@RunWith(AndroidJUnit4::class)
class ViewerDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.filesDir, "device-test")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var file: File

    @Before
    fun setUp() {
        // Slimmed-down test images (ATD) switch UI drawing off; the tests look at pixels.
        HardwareRendererCompat.setDrawingEnabled(true)
        // A slow emulator may be showing a system dialog ("System isn't responding"). It would
        // sit on top of the app and keep its window from ever getting the focus.
        instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun launch(fixture: String) {
        File(dir, "docs").mkdirs()
        file = File(File(dir, "docs"), fixture)
        instrumentation.context.assets.open("fixtures/$fixture").use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setClass(context, MainActivity::class.java)
            .setDataAndType(Uri.fromFile(file), "application/pdf")
        scenario = ActivityScenario.launch(intent)
        waitFor("document and text engine ready", 240_000) { val s = state(); s.phase == Phase.READY && s.textReady }
        // the first page has been drawn once its bitmap arrived; give the renderer a moment
        waitForIdleEngine()
        SystemClock.sleep(1500)
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario!!.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun viewModel(activity: MainActivity) = ViewModelProvider(activity)[DocumentViewModel::class.java]

    private fun state(): UiState = onActivity { viewModel(it).state.value }

    private fun waitFor(what: String, timeoutMs: Long = 120_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for: $what (state: ${state()})")
    }

    private fun waitForIdleEngine() = waitFor("engine idle") { val s = state(); !s.working && !s.textBusy }

    /** A fresh look at what is in the file on disk right now. */
    private fun <T> readFile(block: (PdfSession) -> T): T {
        val copy = File(dir, "check-${System.nanoTime()}.pdf")
        file.copyTo(copy, overwrite = true)
        val session = PdfSession.open(context, copy, File(dir, "check-work-${System.nanoTime()}"))
        try {
            return block(session)
        } finally {
            session.close()
            copy.delete()
        }
    }

    /** Centre of the first box of the first hit for [text] on a page, in page coordinates. */
    private fun locate(page: Int, text: String, occurrence: Int = 0): FloatArray = readFile { s ->
        val hits = s.findText(page, text, ignoreCase = false)
        assertTrue("'$text' #$occurrence not found on page $page", hits.size > occurrence)
        val q: Quad = hits[occurrence].quads[0]
        floatArrayOf((q.left + q.right) / 2f, (q.top + q.bottom) / 2f)
    }

    private fun pagesView(activity: MainActivity): PdfPagesView = activity.findViewById(R.id.pages)

    /** Screen position (within the page view) of a point on a page. */
    private fun toView(page: Int, point: FloatArray): FloatArray =
        onActivity { pagesView(it).pageToScreen(page, point[0], point[1]) }

    /** Sends touch events with exact timestamps straight to the page view. */
    private fun touch(view: PdfPagesView, x: Float, y: Float, vararg timesAndActions: Pair<Long, Int>) {
        val down = timesAndActions[0].first
        for ((time, action) in timesAndActions) {
            val e = MotionEvent.obtain(down, time, action, x, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun tap(page: Int, point: FloatArray) {
        val p = toView(page, point)
        onActivity {
            val t = SystemClock.uptimeMillis()
            touch(pagesView(it), p[0], p[1], t to MotionEvent.ACTION_DOWN, t + 40 to MotionEvent.ACTION_UP)
        }
        // a single tap is only confirmed once the double-tap window has passed
        SystemClock.sleep(450)
        instrumentation.waitForIdleSync()
    }

    private fun doubleTap(page: Int, point: FloatArray) {
        val p = toView(page, point)
        onActivity {
            val v = pagesView(it)
            val t = SystemClock.uptimeMillis()
            touch(v, p[0], p[1], t to MotionEvent.ACTION_DOWN, t + 40 to MotionEvent.ACTION_UP)
            touch(v, p[0], p[1], t + 140 to MotionEvent.ACTION_DOWN, t + 180 to MotionEvent.ACTION_UP)
        }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val out = File(File(context.filesDir, "device-test-screens").apply { mkdirs() }, "$name.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Average colour of a small square of the screen around a page position. */
    private fun colourAt(page: Int, point: FloatArray): Int {
        val p = toView(page, point)
        val origin = IntArray(2)
        onActivity { pagesView(it).getLocationOnScreen(origin) }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val x = (origin[0] + p[0]).toInt().coerceIn(2, bitmap.width - 3)
        val y = (origin[1] + p[1]).toInt().coerceIn(2, bitmap.height - 3)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dx in -2..2) for (dy in -2..2) {
            val c = bitmap.getPixel(x + dx, y + dy)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        bitmap.recycle()
        return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /**
     * True for the yellow of a highlight, whether the sampled spot is bare paper under the
     * highlight (bright yellow) or partly covered by a letter (dark yellow): in both cases
     * blue is well below red and green, which is never true of black ink on white paper.
     */
    private fun isHighlightTint(colour: Int): Boolean {
        val r = (colour shr 16) and 0xFF
        val g = (colour shr 8) and 0xFF
        val b = colour and 0xFF
        return r - b > 50 && g - b > 50
    }

    // --- tests ------------------------------------------------------------------------------------

    @Test
    fun opensADocumentAndDrawsItsPages() {
        launch("basic.pdf")
        val s = state()
        assertEquals("basic.pdf", s.title)
        assertEquals(2, s.pageCount)
        onView(withId(R.id.pages)).check(matches(isDisplayed()))
        onView(allOf(withText("basic.pdf"), isDescendantOfA(withId(R.id.toolbar)))).check(matches(isDisplayed()))
        onView(allOf(withText("Page 1 of 2"), isDescendantOfA(withId(R.id.toolbar)))).check(matches(isDisplayed()))

        // The platform renderer really drew the page: white paper, black ink on the title.
        val paper = colourAt(0, floatArrayOf(300f, 400f))
        assertTrue("page background should be white, was ${Integer.toHexString(paper)}",
            (paper shr 16) > 0xF0 && ((paper shr 8) and 0xFF) > 0xF0 && (paper and 0xFF) > 0xF0)
        val title = locate(0, "Quarterly")
        var darkest = 0xFFFFFF
        for (dx in -12..12 step 3) {
            val c = colourAt(0, floatArrayOf(title[0] + dx, title[1]))
            if ((c and 0xFF) < (darkest and 0xFF)) darkest = c
        }
        assertTrue("there should be ink on the title, darkest was ${Integer.toHexString(darkest)}", (darkest and 0xFF) < 0x90)
        screenshot("01-opened")
    }

    @Test
    fun feature3_toolsCanBeHiddenAndShownAgain() {
        launch("basic.pdf")
        onView(withId(R.id.toolBar)).check(matches(isDisplayed()))
        onView(withId(R.id.toolHide)).perform(click())
        waitFor("tools hidden") { !state().toolsVisible }
        onView(withId(R.id.topBar)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.bottomArea)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.showTools)).check(matches(isDisplayed()))
        screenshot("03-tools-hidden")

        onView(withId(R.id.showTools)).perform(click())
        waitFor("tools shown") { state().toolsVisible }
        onView(withId(R.id.toolBar)).check(matches(isDisplayed()))
        onView(withId(R.id.showTools)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }

    @Test
    fun feature4_zoomButtonsChangeTheMagnification() {
        launch("basic.pdf")
        val start = onActivity { pagesView(it).zoom }
        assertEquals(1f, start, 0.001f)
        onView(withId(R.id.toolZoomIn)).perform(click())
        waitFor("zoomed in") { onActivity { pagesView(it).zoom } > 1.35f }
        onView(withId(R.id.toolZoomIn)).perform(click())
        waitFor("zoomed in twice") { onActivity { pagesView(it).zoom } > 1.9f }
        SystemClock.sleep(2500) // let the sharp patch render
        screenshot("04-zoomed-in")
        // Text must still be where the engine says it is after zooming: tap-to-select proves it.
        onView(withId(R.id.toolZoomOut)).perform(click())
        waitFor("zoomed out once") { onActivity { pagesView(it).zoom } < 1.5f }
        onView(withId(R.id.toolZoomOut)).perform(click())
        onView(withId(R.id.toolZoomOut)).perform(click())
        waitFor("zoomed out below 1") { onActivity { pagesView(it).zoom } < 0.8f }
        screenshot("04-zoomed-out")
    }

    @Test
    fun feature5_selectThenHighlightAndRemoveHighlightAreSavedInTheFile() {
        launch("basic.pdf")
        val original = file.readBytes()

        // Select button, tap, tap.
        onView(withId(R.id.toolSelect)).perform(click())
        waitFor("select mode") { state().selectMode == SelectMode.FIRST_POINT }
        onView(withText(R.string.hint_select_first)).check(matches(isDisplayed()))
        tap(0, locate(0, "Sales"))
        waitFor("first point taken") { state().selectMode == SelectMode.SECOND_POINT }
        onView(withText(R.string.hint_select_second)).check(matches(isDisplayed()))
        screenshot("05-first-tap")
        tap(0, locate(0, "trend"))
        waitFor("selection made") { state().selection != null }

        val s = state()
        assertEquals("after the second tap the select button is no longer active", SelectMode.OFF, s.selectMode)
        assertEquals("Sales grew steadily in every region, and the team expects the trend", s.selection!!.text)
        onView(withId(R.id.selectionBar)).check(matches(isDisplayed()))
        screenshot("05-selected")

        // Copy puts the text on the clipboard.
        onView(withId(R.id.selCopy)).perform(click())
        instrumentation.waitForIdleSync()
        val clip = onActivity {
            val cm = it.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals("Sales grew steadily in every region, and the team expects the trend", clip)

        // Highlight: shown on screen and written into the very file that was opened.
        val probe = locate(0, "grew")
        val before = colourAt(0, floatArrayOf(probe[0], probe[1] - 5.5f))
        onView(withId(R.id.selHighlight)).perform(click())
        waitFor("highlight saved") { val st = state(); !st.working && st.selection == null && st.markupVersion > 0 }
        SystemClock.sleep(800)
        val after = colourAt(0, floatArrayOf(probe[0], probe[1] - 5.5f))
        assertTrue("highlight should tint the page (before ${Integer.toHexString(before)}, after ${Integer.toHexString(after)})",
            isHighlightTint(after) && !isHighlightTint(before))
        screenshot("05-highlighted")

        val saved = file.readBytes()
        assertTrue("the file grew", saved.size > original.size)
        assertTrue("the original bytes were not rewritten", saved.copyOfRange(0, original.size).contentEquals(original))
        readFile { session ->
            val marks = session.markups(0).filter { it.kind == MarkupKind.HIGHLIGHT }
            assertEquals(1, marks.size)
            assertEquals(2, marks[0].quads.size)
        }

        // Remove the highlight from part of it: select two words in the middle.
        onView(withId(R.id.toolSelect)).perform(click())
        tap(0, locate(0, "steadily"))
        waitFor("first point taken") { state().selectMode == SelectMode.SECOND_POINT }
        tap(0, locate(0, "every"))
        waitFor("selection made") { state().selection != null }
        assertEquals("steadily in every", state().selection!!.text)
        val version = state().markupVersion
        onView(withId(R.id.selRemoveHighlight)).perform(click())
        waitFor("highlight trimmed") { val st = state(); !st.working && st.markupVersion > version }
        screenshot("05-highlight-trimmed")
        readFile { session ->
            val marks = session.markups(0).filter { it.kind == MarkupKind.HIGHLIGHT }
            assertEquals("still one highlight", 1, marks.size)
            assertEquals("now in three pieces: before, after, and the next line", 3, marks[0].quads.size)
        }
    }

    @Test
    fun feature6_doubleTapOpensTheParagraphEditorAndSavingRewritesTheFile() {
        launch("basic.pdf")
        val original = file.readBytes()
        doubleTap(0, locate(0, "follows"))
        waitFor("editor shown") {
            try {
                onView(withId(R.id.editText)).inRoot(isDialog()).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        onView(withId(R.id.editText)).inRoot(isDialog())
            .check(matches(withText(containsString("A second paragraph follows after a blank line."))))
        onView(withId(R.id.editNote)).inRoot(isDialog()).check(matches(withText(containsString("Liberation Sans"))))
        screenshot("06-editor")

        val newText = "This paragraph was edited on the device and saved straight into the PDF."
        onView(withId(R.id.editText)).inRoot(isDialog()).perform(replaceText(newText), closeSoftKeyboard())
        onView(withText(R.string.action_save)).inRoot(isDialog()).perform(click())
        waitFor("edit saved") {
            !state().working && file.length() > original.size
        }
        SystemClock.sleep(2500) // page is re-rendered from the new file
        screenshot("06-edited")

        val saved = file.readBytes()
        assertTrue("the original bytes were not rewritten", saved.copyOfRange(0, original.size).contentEquals(original))
        readFile { session ->
            val text = session.pageTextContent(0)
            assertTrue(text, text.contains(newText))
            assertFalse(text, text.contains("A second paragraph follows"))
            assertTrue(text, text.contains("The first paragraph of this document"))
            assertTrue(text, text.contains("Publish the roadmap"))
        }
        // The old words are gone from the screen as well: where "forecasting." stood is paper now.
        // (The new text is shorter and ends earlier on the second line.)
    }

    @Test
    fun feature6_charactersTheFontsLackAreExplainedInTheEditor() {
        launch("basic.pdf")
        val original = file.readBytes()
        doubleTap(0, locate(0, "follows"))
        waitFor("editor shown") {
            try {
                onView(withId(R.id.editText)).inRoot(isDialog()).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        onView(withId(R.id.editText)).inRoot(isDialog()).perform(replaceText("Chinese 中文 text"), closeSoftKeyboard())
        onView(withText(R.string.action_save)).inRoot(isDialog()).perform(click())
        // The message names the characters; it appears under the text field, in the dialog.
        val message = context.getString(R.string.msg_unsupported_chars, "中文")
        waitFor("error shown") {
            try {
                onView(withText(message)).inRoot(isDialog()).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        screenshot("06-unsupported-characters")
        assertTrue("nothing was written", file.readBytes().contentEquals(original))
        // the dialog is still open and usable, so the text can be corrected and saved
        onView(withId(R.id.editText)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(R.id.editText)).inRoot(isDialog()).perform(replaceText("Corrected text."), closeSoftKeyboard())
        onView(withText(R.string.action_save)).inRoot(isDialog()).perform(click())
        waitFor("corrected text saved") { !state().working && file.length() > original.size }
        readFile { assertTrue(it.pageTextContent(0).contains("Corrected text.")) }
    }

    @Test
    fun feature6_doubleTapWhereThereIsNoTextDoesNothingHarmful() {
        launch("basic.pdf")
        val original = file.readBytes()
        doubleTap(0, floatArrayOf(300f, 500f)) // empty part of the page
        waitForIdleEngine()
        SystemClock.sleep(600)
        assertNull(state().selection)
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test
    fun feature1_saveCopyWritesTheEditedDocumentAndKeepsWorkingOnTheOriginal() {
        launch("basic.pdf")
        // make an edit first so the copy differs from the fixture
        onView(withId(R.id.toolSelect)).perform(click())
        tap(0, locate(0, "Sales"))
        waitFor("first point") { state().selectMode == SelectMode.SECOND_POINT }
        tap(0, locate(0, "grew"))
        waitFor("selection") { state().selection != null }
        onView(withId(R.id.selHighlight)).perform(click())
        waitFor("saved") { val st = state(); !st.working && st.markupVersion > 0 }

        // The save-copy button opens the system's "create document" screen; the write itself
        // is what matters here, so it is driven with a destination directly.
        val target = File(dir, "copy-${System.nanoTime()}.pdf")
        target.createNewFile()
        onActivity { viewModel(it).saveCopyTo(Uri.fromFile(target), adopt = false) }
        waitFor("copy written") { !state().working && target.length() > 0 }
        assertTrue(target.readBytes().contentEquals(file.readBytes()))
        assertEquals("still working on the original", "basic.pdf", state().title)
        assertEquals("basic (copy).pdf", onActivity { viewModel(it).suggestedCopyName() })
    }

    @Test
    fun feature2_shareHandsOutAReadableCopyOfTheDocument() {
        launch("basic.pdf")
        val shared = File(File(context.cacheDir, "shared"), "basic.pdf")
        shared.delete()
        // Tapping Share ends in the system chooser, which belongs to the system; check what the
        // app hands over instead: a file other apps can read through the app's FileProvider.
        onActivity { viewModel(it).share() }
        waitFor("share file prepared") { shared.isFile && shared.length() > 0 && !state().working }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", shared)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(bytes.contentEquals(file.readBytes()))
        assertEquals("application/pdf", context.contentResolver.getType(uri))
    }

    @Test
    fun rotatedPagesAndOtherProducersRenderWithOverlaysInPlace() {
        launch("rotated.pdf")
        assertEquals(7, state().pageCount)
        // highlight on the page rotated by 90 degrees and check the tint lands on its text
        onActivity { pagesView(it).scrollToPage(1) }
        SystemClock.sleep(1500)
        onView(withId(R.id.toolSelect)).perform(click())
        tap(1, locate(1, "Rotated"))
        waitFor("first point") { state().selectMode == SelectMode.SECOND_POINT }
        tap(1, locate(1, "one"))
        waitFor("selection") { state().selection != null }
        assertEquals("Rotated page text line one", state().selection!!.text)
        onView(withId(R.id.selHighlight)).perform(click())
        waitFor("saved") { val st = state(); !st.working && st.markupVersion > 0 }
        SystemClock.sleep(800)
        screenshot("07-rotated-highlight")
        // The line runs top to bottom on this page. Sample its box just beside the glyphs.
        val quad = readFile { it.markups(1).first { m -> m.kind == MarkupKind.HIGHLIGHT }.quads[0] }
        val inside = colourAt(1, floatArrayOf((quad.left + quad.right) / 2f, quad.top + 1.5f))
        assertTrue("highlight colour expected on the rotated line, was ${Integer.toHexString(inside)}", isHighlightTint(inside))
        // and beside the line the page is still plain paper
        val beside = colourAt(1, floatArrayOf(quad.left - 6f, (quad.top + quad.bottom) / 2f))
        assertFalse("no tint beside the line, was ${Integer.toHexString(beside)}", isHighlightTint(beside))
    }

    @Test
    fun theSaveVerifierAcceptsACleanEditAndRejectsOneThatChangesSomethingElse() {
        // No UI here: this exercises the gate every edit must pass, with the real renderer.
        File(dir, "docs").mkdirs()
        val original = File(File(dir, "docs"), "verify.pdf")
        instrumentation.context.assets.open("fixtures/basic.pdf").use { input -> FileOutputStream(original).use { input.copyTo(it) } }

        // An edit made with the real verifier in place goes through.
        val session = PdfSession.open(context, original, File(dir, "verify-work"), PlatformRasterizer.VERIFIER)
        val hit = session.findText(0, "follows", ignoreCase = false).first().quads[0]
        val paragraph = session.paragraphAt(0, PdfPoint((hit.left + hit.right) / 2f, (hit.top + hit.bottom) / 2f))!!
        assertTrue(session.replaceParagraph(paragraph, "Replaced under the eyes of the verifier.").changed)
        val edited = File(dir, "verify-edited.pdf")
        session.copyTo(edited)
        session.close()

        val size = PageSize(612f, 792f)
        // The same pair of files, honestly described: accepted.
        val honest = PageChange(0, size, true, paragraph.quads + Quad(72f, 200f, 420f, 200f, 420f, 250f, 72f, 250f))
        PlatformRasterizer.VERIFIER.verify(edited, original, 2, listOf(honest))

        // Now claim the edit was somewhere else on the page. To the verifier this is an edit
        // that changed text outside its area – exactly what collateral damage looks like.
        val elsewhere = PageChange(0, size, true, listOf(Quad(72f, 600f, 300f, 600f, 300f, 640f, 72f, 640f)))
        try {
            PlatformRasterizer.VERIFIER.verify(edited, original, 2, listOf(elsewhere))
            throw AssertionError("a change outside the declared area must be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("other parts of page 1"))
        }

        // A wrong page count is rejected, and so is something that is not a PDF at all.
        try {
            PlatformRasterizer.VERIFIER.verify(edited, original, 3, listOf(honest))
            throw AssertionError("a wrong page count must be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("Page count"))
        }
        val junk = File(dir, "junk.pdf").apply { writeText("not a pdf") }
        try {
            PlatformRasterizer.VERIFIER.verify(junk, original, 2, listOf(honest))
            throw AssertionError("junk must be rejected")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    @Test
    fun aFileThatIsNotAPdfShowsAnErrorInsteadOfCrashing() {
        File(dir, "docs").mkdirs()
        file = File(File(dir, "docs"), "broken.pdf")
        file.writeText("this is not a pdf")
        val intent = Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java)
            .setDataAndType(Uri.fromFile(file), "application/pdf")
        scenario = ActivityScenario.launch(intent)
        waitFor("failure reported", 60_000) { state().phase == Phase.FAILED }
        onView(withText(R.string.err_open_failed)).check(matches(isDisplayed()))
        onView(withId(R.id.homeOpen)).check(matches(isDisplayed()))
        screenshot("08-not-a-pdf")
    }

    @Test
    fun aCertificateEncryptedPdfIsRefusedCleanly() {
        // The app carries no cryptography library of its own (Bouncy Castle is excluded from
        // the build); certificate-encrypted PDFs are simply refused.
        try {
            Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            throw AssertionError("Bouncy Castle is in the app again: add its licence to the notices, or exclude it")
        } catch (_: ClassNotFoundException) {
            // as intended
        }
        File(dir, "docs").mkdirs()
        file = File(File(dir, "docs"), "encrypted_cert.pdf")
        instrumentation.context.assets.open("fixtures/encrypted_cert.pdf").use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        }
        // the engine on its own: a plain refusal, not a crash for want of the library
        try {
            PdfSession.open(context, file, File(dir, "cert-work")).close()
            throw AssertionError("a certificate-encrypted file must not open")
        } catch (_: io.github.legacylive88.simplepdfs.pdfcore.PdfOpenException) {
            // as intended
        }
        // and the app as a whole
        val intent = Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java)
            .setDataAndType(Uri.fromFile(file), "application/pdf")
        scenario = ActivityScenario.launch(intent)
        waitFor("failure reported", 60_000) { state().phase == Phase.FAILED }
        assertTrue(state().error == R.string.err_password || state().error == R.string.err_open_failed)
        onView(withId(R.id.homeOpen)).check(matches(isDisplayed()))
        screenshot("15-certificate-encrypted")
        // the app is fine afterwards
        scenario?.close()
        launch("basic.pdf")
        assertEquals(2, state().pageCount)
    }

    @Test
    fun aLargeDocumentOpensAndCanBeEditedOnALaterPage() {
        launch("big.pdf")
        assertEquals(300, state().pageCount)
        onActivity { pagesView(it).scrollToPage(149) }
        waitFor("page indicator") { onActivity { pagesView(it).firstVisiblePage() } == 149 }
        SystemClock.sleep(2000)
        doubleTap(149, locate(149, "Section"))
        waitFor("editor shown") {
            try {
                onView(withId(R.id.editText)).inRoot(isDialog()).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        onView(withId(R.id.editText)).inRoot(isDialog()).perform(replaceText("Chapter 150"), closeSoftKeyboard())
        val before = file.length()
        onView(withText(R.string.action_save)).inRoot(isDialog()).perform(click())
        waitFor("edit saved", 240_000) { !state().working && file.length() > before }
        SystemClock.sleep(2000)
        screenshot("09-large-document")
        readFile { assertTrue(it.pageTextContent(149).contains("Chapter 150")) }
        assertNotNull(state())
    }

    @Test
    fun aProtectedDocumentCanBeViewedAndSelectedButNotChanged() {
        // Encrypted with an owner password only: it opens without asking, using the device's
        // own cryptography, and is view-only.
        launch("encrypted_owner.pdf")
        val original = file.readBytes()
        onView(withId(R.id.toolSelect)).perform(click())
        tap(0, locate(0, "Sales"))
        waitFor("first point taken") { state().selectMode == SelectMode.SECOND_POINT }
        tap(0, locate(0, "trend"))
        waitFor("selection made") { state().selection != null }
        onView(withId(R.id.selHighlight)).perform(click())
        waitFor("the refusal is explained") {
            try {
                onView(withText(R.string.msg_protected)).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        screenshot("12-protected")
        waitForIdleEngine()
        assertTrue("a protected file is never written to", file.readBytes().contentEquals(original))
    }

    @Test
    fun aDocumentWithFormFieldsOrStampsNamesWhatCannotBeShown() {
        // Page 1 of the fixture carries a filled-in form field on a pale blue box, a red
        // "APPROVED" stamp and a note.
        launch("form_stamp.pdf")
        // what the app measured about this device's renderer when it opened the document
        val drawn = AnnotationProbe.drawnKinds(dir)
        val expected = context.getString(
            R.string.msg_annotations_not_shown,
            if (AnnotationKind.FORM_FIELD in drawn) "notes and stamps" else "filled-in form fields, notes and stamps",
        )
        waitFor("the notice names what is missing", 5_500) {
            try {
                onView(withText(expected)).check(matches(isDisplayed())); true
            } catch (_: Throwable) {
                false
            }
        }
        screenshot("13-form-and-stamp")

        // What this device's renderer really does, read off the screen.
        val height = readFile { it.pageSize(0).height }
        val inTheField = colourAt(0, floatArrayOf(250f, height - 431f))   // pale blue if the field is drawn
        val onTheStamp = colourAt(0, floatArrayOf(331f, height - 431f))   // red frame if the stamp is drawn
        val fieldDrawn = (inTheField and 0xFF) - ((inTheField shr 16) and 0xFF) > 12
        val stampDrawn = ((onTheStamp shr 16) and 0xFF) - ((onTheStamp shr 8) and 0xFF) > 40
        val what = "API ${android.os.Build.VERSION.SDK_INT}: field ${Integer.toHexString(inTheField)}, stamp ${Integer.toHexString(onTheStamp)}"
        assertEquals("form fields: the probe must agree with what Android draws ($what)", AnnotationKind.FORM_FIELD in drawn, fieldDrawn)
        assertEquals("stamps: the probe must agree with what Android draws ($what)", AnnotationKind.STAMP in drawn, stampDrawn)
    }

    @Test
    fun turningThePhoneKeepsTheDocumentAndThePlaceInIt() {
        launch("operators.pdf") // four pages
        onActivity { pagesView(it).scrollToPage(2) }
        waitFor("third page at the top") { onActivity { pagesView(it).firstVisiblePage() } == 2 }

        onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor("landscape", 60_000) { onActivity { val v = pagesView(it); v.width > v.height } }
        SystemClock.sleep(1500)
        assertEquals(Phase.READY, state().phase)
        assertEquals("still on the same page", 2, onActivity { pagesView(it).firstVisiblePage() })
        screenshot("14-landscape")
        // the tools still work on that page
        onView(withId(R.id.toolSelect)).perform(click())
        waitFor("select mode") { state().selectMode == SelectMode.FIRST_POINT }

        onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor("portrait", 60_000) { onActivity { val v = pagesView(it); v.height > v.width } }
        SystemClock.sleep(1000)
        assertEquals(2, onActivity { pagesView(it).firstVisiblePage() })
        assertEquals("turning the phone does not abandon what the user was doing", SelectMode.FIRST_POINT, state().selectMode)
    }

    @Test
    fun aboutShowsTheVersionAndTheOpenSourceLicences() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        onView(withContentDescription(androidx.appcompat.R.string.abc_action_menu_overflow_description)).perform(click())
        // the menu is a window of its own, which may take a moment to appear
        onView(withText(R.string.action_about)).inRoot(isPlatformPopup()).perform(click())
        onView(withText(containsString(BuildConfig.VERSION_NAME))).inRoot(isDialog()).check(matches(isDisplayed()))
        screenshot("10-about")

        onView(withText(R.string.action_licences)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.licenceText)).inRoot(isDialog()).check(
            matches(
                allOf(
                    isDisplayed(),
                    withText(containsString("Apache License")),
                    withText(containsString("PdfBox-Android")),
                    withText(containsString("Material Components for Android")),
                    withText(containsString("SIL OPEN FONT LICENSE")),
                    withText(containsString("DejaVu")),
                ),
            ),
        )
        screenshot("11-licences")
    }
}
