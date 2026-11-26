package io.github.legacylive88.simplepdfs

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.legacylive88.simplepdfs.pdfcore.AnnotationKind
import io.github.legacylive88.simplepdfs.pdfcore.MarkupKind
import io.github.legacylive88.simplepdfs.pdfcore.PdfSession
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The app's behaviour, tested without a device: the real engine and the real file handling,
 * with only the pixel renderer replaced. What the user's file contains afterwards is always
 * checked by opening it again from scratch.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: DocumentViewModel
    private lateinit var rasterizers: FakeRasterizer.Factory
    private val events = ArrayList<UiEvent>()
    private var collector: Job? = null
    private lateinit var file: File

    @Before
    fun setUp() {
        rasterizers = FakeRasterizer.Factory()
        vm = DocumentViewModel(app, rasterizers, reloadVerifier, Dispatchers.IO)
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.events.collect { events.add(it) } }
        Robolectric.setupContentProvider(TestDocumentProvider::class.java, TestDocumentProvider.AUTHORITY)
        TestDocumentProvider.writable = true
        TestDocumentProvider.seekable = true
    }

    @After
    fun tearDown() {
        collector?.cancel()
        vm.closeDocument()
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun fixture(name: String): File {
        val f = File(tmp.newFolder(), name)
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun open(name: String): File {
        file = fixture(name)
        vm.open(Uri.fromFile(file), persist = false)
        awaitMain("document ready") { vm.state.value.phase == Phase.READY && vm.state.value.textReady }
        return file
    }

    private fun idle() = awaitMain("idle") { !vm.state.value.working && !vm.state.value.textBusy }

    /** What is in a file right now, read with a brand new engine instance. */
    private fun <T> inspect(f: File, block: (PdfSession) -> T): T {
        val copy = File(tmp.newFolder(), "inspect.pdf")
        f.copyTo(copy)
        val s = PdfSession.open(app, copy, File(copy.parentFile, "work"))
        try {
            return block(s)
        } finally {
            s.close()
        }
    }

    private fun centre(q: Quad) = floatArrayOf((q.left + q.right) / 2f, (q.top + q.bottom) / 2f)

    private fun locate(page: Int, text: String): FloatArray =
        inspect(file) { centre(it.findText(page, text, ignoreCase = false).first().quads[0]) }

    private fun tap(page: Int, text: String) {
        val p = locate(page, text)
        vm.onTap(page, p[0], p[1])
    }

    private fun select(page: Int, from: String, to: String) {
        vm.toggleSelectMode()
        tap(page, from)
        tap(page, to)
        awaitMain("selection") { vm.state.value.selection != null }
    }

    private inline fun <reified T : UiEvent> lastEvent(): T? = events.filterIsInstance<T>().lastOrNull()

    private fun messages(): List<Int> = events.filterIsInstance<UiEvent.Message>().map { it.text }

    // --- opening ----------------------------------------------------------------------------------

    @Test
    fun openingADocumentShowsItAndThenMakesTextToolsAvailable() {
        open("basic.pdf")
        val s = vm.state.value
        assertEquals("basic.pdf", s.title)
        assertEquals(2, s.pageCount)
        assertTrue(s.toolsVisible)
        assertEquals(SelectMode.OFF, s.selectMode)
        assertFalse(s.editsNotInOriginal)
        assertEquals(2, vm.pageCount)
        assertEquals(612f, vm.pageSize(0)[0], 0.01f)
        assertEquals(792f, vm.pageSize(0)[1], 0.01f)
        assertEquals(Uri.fromFile(file), vm.currentUri)
        assertEquals("the document is remembered", "basic.pdf", vm.recents.list().first().name)
    }

    @Test
    fun pagesOfDifferentSizesAreMeasuredInTheBackground() {
        open("rotated.pdf")
        awaitMain("all pages measured") { vm.pageSize(1)[0] == 792f && vm.pageSize(4)[0] == 700f }
        assertEquals(612f, vm.pageSize(1)[1], 0.01f)
        assertEquals(464f, vm.pageSize(4)[1], 0.01f)
        assertTrue("the screen is told to lay pages out again", vm.state.value.layoutVersion > 0)
    }

    @Test
    fun aFileThatIsNotAPdfIsReportedAndForgotten() {
        file = File(tmp.newFolder(), "notes.pdf")
        file.writeText("not a pdf")
        vm.open(Uri.fromFile(file), persist = false)
        awaitMain("failure") { vm.state.value.phase == Phase.FAILED }
        assertEquals(R.string.err_open_failed, vm.state.value.error)
        assertEquals("notes.pdf", vm.state.value.title)
        assertTrue(vm.recents.list().none { it.uri == Uri.fromFile(file) })
        assertNull(vm.currentUri)
    }

    @Test
    fun aPasswordProtectedFileIsExplained() {
        file = fixture("encrypted_user.pdf")
        vm.open(Uri.fromFile(file), persist = false)
        awaitMain("failure") { vm.state.value.phase == Phase.FAILED }
        assertEquals(R.string.err_password, vm.state.value.error)
    }

    @Test
    fun aDocumentWithFormFieldsOrStampsNamesWhatCannotBeShown() {
        // Android's renderer draws no annotations before Android 15 (the fake behaves the same)
        open("form_stamp.pdf")
        awaitMain("notice") { messages().contains(R.string.msg_annotations_not_shown) }
        val notices = events.filterIsInstance<UiEvent.Message>().filter { it.text == R.string.msg_annotations_not_shown }
        assertEquals("said once", 1, notices.size)
        assertEquals("filled-in form fields, notes and stamps", notices[0].argument)
        assertTrue("stays up long enough to be read", notices[0].long)

        // highlights are drawn by the app itself and ordinary documents have nothing to miss
        events.clear()
        for (name in listOf("annotated.pdf", "basic.pdf")) {
            open(name)
            // a selection goes through the engine after the check, so the check is over by now
            select(0, "Sales", "trend")
            idle()
            assertFalse(name, messages().contains(R.string.msg_annotations_not_shown))
        }
    }

    @Test
    fun onlyWhatTheRendererLeavesOutIsMentioned() {
        // Android 15 and newer draw form fields, but still no stamps or notes
        rasterizers.drawnAnnotationKinds = setOf(AnnotationKind.FORM_FIELD)
        open("form_stamp.pdf")
        awaitMain("notice") { messages().contains(R.string.msg_annotations_not_shown) }
        assertEquals("notes and stamps", lastEvent<UiEvent.Message>()!!.argument)

        // a renderer that draws everything leaves nothing to say
        events.clear()
        rasterizers.drawnAnnotationKinds = AnnotationKind.entries.toSet()
        open("form_stamp.pdf")
        select(0, "Sales", "trend")
        idle()
        assertFalse(messages().contains(R.string.msg_annotations_not_shown))
    }

    @Test
    fun openingAnotherDocumentReplacesTheFirstAndClosingReturnsToTheStartScreen() {
        open("basic.pdf")
        val first = file
        open("justified.pdf")
        assertEquals("justified.pdf", vm.state.value.title)
        assertEquals(1, vm.state.value.pageCount)
        awaitMain("first document released") { synchronized(rasterizers.log) { rasterizers.log.any { it.startsWith("close ") } } }
        vm.closeDocument()
        assertEquals(Phase.EMPTY, vm.state.value.phase)
        assertNull(vm.currentUri)
        assertEquals(0, vm.pageCount)
        assertTrue(first.exists())
    }

    // --- feature 3 --------------------------------------------------------------------------------

    @Test
    fun toolsCanBeHiddenAndShown() {
        open("basic.pdf")
        vm.setToolsVisible(false)
        assertFalse(vm.state.value.toolsVisible)
        vm.setToolsVisible(true)
        assertTrue(vm.state.value.toolsVisible)
    }

    // --- feature 5 --------------------------------------------------------------------------------

    @Test
    fun selectButtonThenTwoTapsSelectsTheTextBetweenThemAndSwitchesSelectOff() {
        open("basic.pdf")
        vm.toggleSelectMode()
        assertEquals(SelectMode.FIRST_POINT, vm.state.value.selectMode)

        tap(0, "Sales")
        awaitMain("first point") { vm.state.value.selectMode == SelectMode.SECOND_POINT }
        assertEquals(0, vm.state.value.anchorPage)
        assertEquals("the tapped word is marked", 1, vm.state.value.anchorQuads.size)
        assertNull(vm.state.value.selection)

        tap(0, "trend")
        awaitMain("selection") { vm.state.value.selection != null }
        val s = vm.state.value
        assertEquals("Sales grew steadily in every region, and the team expects the trend", s.selection!!.text)
        assertEquals("select is no longer active after the second tap", SelectMode.OFF, s.selectMode)
        assertEquals(-1, s.anchorPage)
        assertEquals(2, s.selection!!.quads.size)
    }

    @Test
    fun twoQuickTapsAreHandledInOrderEvenWhileTheEngineIsStillLoading() {
        file = fixture("basic.pdf")
        vm.open(Uri.fromFile(file), persist = false)
        awaitMain("pages shown") { vm.state.value.phase == Phase.READY }
        // no waiting for the text engine here
        vm.toggleSelectMode()
        val a = locate(0, "Sales")
        val b = locate(0, "trend")
        vm.onTap(0, a[0], a[1])
        vm.onTap(0, b[0], b[1])
        awaitMain("selection") { vm.state.value.selection != null }
        assertEquals("Sales grew steadily in every region, and the team expects the trend", vm.state.value.selection!!.text)
    }

    @Test
    fun theSecondTapMustBeOnTheSamePage() {
        open("basic.pdf")
        vm.toggleSelectMode()
        tap(0, "Sales")
        awaitMain("first point") { vm.state.value.selectMode == SelectMode.SECOND_POINT }
        tap(1, "single")
        awaitMain("message") { messages().contains(R.string.msg_select_same_page) }
        assertEquals("still waiting for the second tap", SelectMode.SECOND_POINT, vm.state.value.selectMode)
        assertNull(vm.state.value.selection)
        tap(0, "trend")
        awaitMain("selection") { vm.state.value.selection != null }
    }

    @Test
    fun aPageWithoutTextSaysSo() {
        open("scan.pdf")
        vm.toggleSelectMode()
        vm.onTap(0, 200f, 300f)
        awaitMain("message") { messages().contains(R.string.msg_no_text_on_page) }
        assertEquals(SelectMode.FIRST_POINT, vm.state.value.selectMode)
    }

    @Test
    fun pressingSelectAgainAbandonsTheSelectionAndATapClearsAFinishedOne() {
        open("basic.pdf")
        vm.toggleSelectMode()
        tap(0, "Sales")
        awaitMain("first point") { vm.state.value.selectMode == SelectMode.SECOND_POINT }
        vm.toggleSelectMode()
        assertEquals(SelectMode.OFF, vm.state.value.selectMode)
        assertEquals(-1, vm.state.value.anchorPage)

        select(0, "Sales", "trend")
        vm.onTap(0, 300f, 600f)
        assertNull(vm.state.value.selection)
    }

    @Test
    fun copyHandsTheSelectedTextToTheScreen() {
        open("basic.pdf")
        select(0, "second", "blank")
        vm.copySelection()
        awaitMain("copy event") { lastEvent<UiEvent.CopyToClipboard>() != null }
        assertEquals("second paragraph follows after a blank", lastEvent<UiEvent.CopyToClipboard>()!!.text)
        assertNotNull("copying keeps the selection", vm.state.value.selection)
    }

    @Test
    fun highlightIsSavedInstantlyOverTheSameFile() {
        open("basic.pdf")
        val original = file.readBytes()
        select(0, "Sales", "trend")
        vm.highlightSelection()
        awaitMain("saved") { !vm.state.value.working && vm.state.value.markupVersion > 0 }
        awaitMain("written back") { file.length() > original.size }

        assertNull("the selection is done with", vm.state.value.selection)
        assertFalse(vm.state.value.editsNotInOriginal)
        assertEquals("the screen can draw it", 1, vm.markups(0).size)
        // the user's own file now carries the highlight, with its original bytes untouched
        val saved = file.readBytes()
        assertTrue(saved.copyOfRange(0, original.size).contentEquals(original))
        inspect(file) { s ->
            val marks = s.markups(0)
            assertEquals(1, marks.size)
            assertEquals(MarkupKind.HIGHLIGHT, marks[0].kind)
        }
        // highlighting does not change page pixels, so the renderer keeps its file
        assertEquals(1, synchronized(rasterizers.log) { rasterizers.log.count { it.startsWith("open ") } })
    }

    @Test
    fun removeHighlightTrimsAndWithoutAHighlightSaysSo() {
        open("basic.pdf")
        select(0, "Sales", "trend")
        vm.removeHighlightFromSelection()
        awaitMain("message") { messages().contains(R.string.msg_no_highlight_here) }
        assertNotNull("nothing happened, the selection stays", vm.state.value.selection)
        val untouched = file.readBytes()

        vm.highlightSelection()
        awaitMain("highlighted") { file.length() > untouched.size && !vm.state.value.working }
        select(0, "steadily", "every")
        vm.removeHighlightFromSelection()
        awaitMain("trimmed") { !vm.state.value.working && vm.state.value.selection == null }
        idle()
        inspect(file) { s ->
            val marks = s.markups(0)
            assertEquals(1, marks.size)
            assertEquals(3, marks[0].quads.size)
        }
    }

    @Test
    fun aProtectedDocumentCanBeViewedAndSelectedButNotChanged() {
        open("encrypted_owner.pdf")
        val original = file.readBytes()
        select(0, "Sales", "trend")
        assertEquals("its author forbids copying", "", vm.state.value.selection!!.text)
        vm.copySelection()
        awaitMain("message") { messages().contains(R.string.msg_copy_not_allowed) }
        vm.highlightSelection()
        awaitMain("message") { messages().contains(R.string.msg_protected) }
        idle()
        assertTrue(file.readBytes().contentEquals(original))
    }

    // --- feature 6 --------------------------------------------------------------------------------

    @Test
    fun doubleTapOnAParagraphOffersItForEditingAndSavingRewritesTheFile() {
        open("basic.pdf")
        val original = file.readBytes()
        val p = locate(0, "follows")
        vm.onDoubleTap(0, p[0], p[1])
        awaitMain("editor requested") { lastEvent<UiEvent.EditParagraph>() != null }
        val paragraph = lastEvent<UiEvent.EditParagraph>()!!.paragraph
        assertTrue(paragraph.text.startsWith("A second paragraph follows after a blank line."))

        val newText = "Rewritten from the app."
        vm.saveParagraph(paragraph, newText)
        awaitMain("result") { lastEvent<UiEvent.ParagraphResult>() != null }
        assertEquals("0 means saved", 0, lastEvent<UiEvent.ParagraphResult>()!!.error)
        awaitMain("written back") { file.length() > original.size && !vm.state.value.working }

        assertEquals("the screen is told which page to draw again", 0, lastEvent<UiEvent.PageChanged>()!!.pageIndex)
        assertTrue(messages().contains(R.string.msg_paragraph_saved))
        // the renderer was pointed at the new file
        assertEquals(2, synchronized(rasterizers.log) { rasterizers.log.count { it.startsWith("open ") } })
        val saved = file.readBytes()
        assertTrue(saved.copyOfRange(0, original.size).contentEquals(original))
        inspect(file) { s ->
            val text = s.pageTextContent(0)
            assertTrue(text, text.contains(newText))
            assertFalse(text, text.contains("A second paragraph follows"))
        }
    }

    @Test
    fun doubleTapWhileSelectingCountsAsATap() {
        open("basic.pdf")
        vm.toggleSelectMode()
        val p = locate(0, "follows")
        vm.onDoubleTap(0, p[0], p[1])
        awaitMain("first point") { vm.state.value.selectMode == SelectMode.SECOND_POINT }
        assertNull(lastEvent<UiEvent.EditParagraph>())
    }

    @Test
    fun doubleTapAwayFromTextOrOnAScanExplainsWhyNothingOpens() {
        open("basic.pdf")
        vm.onDoubleTap(0, 300f, 600f)
        awaitMain("message") { messages().contains(R.string.msg_no_paragraph) }
        assertNull(lastEvent<UiEvent.EditParagraph>())

        open("scan.pdf")
        val p = locate(1, "Scanned")
        vm.onDoubleTap(1, p[0], p[1])
        awaitMain("message") { messages().contains(R.string.reason_invisible) }
        assertNull(lastEvent<UiEvent.EditParagraph>())
    }

    @Test
    fun charactersTheFontsLackAreReportedToTheEditorAndNothingIsWritten() {
        open("basic.pdf")
        val original = file.readBytes()
        val p = locate(0, "follows")
        vm.onDoubleTap(0, p[0], p[1])
        awaitMain("editor requested") { lastEvent<UiEvent.EditParagraph>() != null }
        vm.saveParagraph(lastEvent<UiEvent.EditParagraph>()!!.paragraph, "Text with 中文 in it")
        awaitMain("result") { lastEvent<UiEvent.ParagraphResult>() != null }
        val result = lastEvent<UiEvent.ParagraphResult>()!!
        assertEquals(R.string.msg_unsupported_chars, result.error)
        assertEquals("中文", result.argument)
        idle()
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test
    fun aLongEditReportsThatTheTextWasShrunkOrOverflows() {
        open("basic.pdf")
        val p = locate(0, "follows")
        vm.onDoubleTap(0, p[0], p[1])
        awaitMain("editor requested") { lastEvent<UiEvent.EditParagraph>() != null }
        val huge = (1..60).joinToString(" ") { "Sentence number $it keeps going." }
        vm.saveParagraph(lastEvent<UiEvent.EditParagraph>()!!.paragraph, huge)
        awaitMain("result") { lastEvent<UiEvent.ParagraphResult>() != null }
        assertEquals(0, lastEvent<UiEvent.ParagraphResult>()!!.error)
        assertTrue(messages().contains(R.string.msg_paragraph_saved_overflow))
    }

    // --- saving over the same file ------------------------------------------------------------------

    @Test
    fun aReadOnlyDocumentKeepsEditsInTheAppUntilTheyAreSavedSomewhereElse() {
        val source = fixture("basic.pdf")
        val original = source.readBytes()
        TestDocumentProvider.file = source
        TestDocumentProvider.writable = false
        TestDocumentProvider.displayName = "Attachment.pdf"
        file = source
        vm.open(TestDocumentProvider.uri(), persist = false)
        awaitMain("ready") { vm.state.value.phase == Phase.READY && vm.state.value.textReady }
        assertEquals("the provider's name is shown", "Attachment.pdf", vm.state.value.title)

        select(0, "Sales", "trend")
        vm.highlightSelection()
        awaitMain("edit applied") { vm.state.value.markupVersion > 0 && !vm.state.value.working }
        awaitMain("flagged") { vm.state.value.editsNotInOriginal }
        assertTrue("the read-only original is untouched", source.readBytes().contentEquals(original))
        assertEquals("the edit is visible in the app", 1, vm.markups(0).size)

        // "Save as…" from the banner: the new file becomes the document from here on
        val target = File(tmp.newFolder(), "Mine.pdf")
        target.createNewFile()
        vm.saveCopyTo(Uri.fromFile(target), adopt = true)
        awaitMain("saved as") { !vm.state.value.editsNotInOriginal && !vm.state.value.working }
        assertEquals("Mine.pdf", vm.state.value.title)
        assertEquals(Uri.fromFile(target), vm.currentUri)
        inspect(target) { assertEquals(1, it.markups(0).size) }

        // and further edits are saved instantly over that new file
        val before = target.length()
        file = target
        select(0, "second", "blank")
        vm.highlightSelection()
        awaitMain("second edit written") { target.length() != before && !vm.state.value.working }
        inspect(target) { assertEquals(2, it.markups(0).size) }
        assertTrue(source.readBytes().contentEquals(original))
    }

    @Test
    fun aProviderWithoutRandomAccessGetsTheWholeFileRewritten() {
        val source = fixture("basic.pdf")
        TestDocumentProvider.file = source
        TestDocumentProvider.writable = true
        TestDocumentProvider.seekable = false
        file = source
        vm.open(TestDocumentProvider.uri(), persist = false)
        awaitMain("ready") { vm.state.value.phase == Phase.READY && vm.state.value.textReady }
        val before = source.length()
        select(0, "Sales", "trend")
        vm.highlightSelection()
        awaitMain("written") { source.length() > before && !vm.state.value.working }
        assertFalse(vm.state.value.editsNotInOriginal)
        inspect(source) { assertEquals(1, it.markups(0).size) }
    }

    // --- feature 1 and 2 ----------------------------------------------------------------------------

    @Test
    fun saveCopyWritesTheDocumentWithItsEditsAndStaysOnTheOriginal() {
        open("basic.pdf")
        select(0, "Sales", "trend")
        vm.highlightSelection()
        awaitMain("saved") { vm.state.value.markupVersion > 0 && !vm.state.value.working }
        idle()

        assertEquals("basic (copy).pdf", vm.suggestedCopyName())
        val target = File(tmp.newFolder(), "elsewhere.pdf")
        target.createNewFile()
        vm.saveCopyTo(Uri.fromFile(target), adopt = false)
        awaitMain("copy saved") { messages().contains(R.string.msg_saved_copy) }
        assertTrue(target.readBytes().contentEquals(file.readBytes()))
        assertEquals("basic.pdf", vm.state.value.title)
        assertEquals(Uri.fromFile(file), vm.currentUri)
        inspect(target) { assertEquals(1, it.markups(0).size) }
    }

    @Test
    fun saveCopyBeforeTheTextEngineIsReadyCopiesTheOriginal() {
        file = fixture("basic.pdf")
        vm.open(Uri.fromFile(file), persist = false)
        awaitMain("pages shown") { vm.state.value.phase == Phase.READY }
        val target = File(tmp.newFolder(), "early.pdf")
        target.createNewFile()
        vm.saveCopyTo(Uri.fromFile(target), adopt = false)
        awaitMain("copy saved") { messages().contains(R.string.msg_saved_copy) }
        assertTrue(target.readBytes().contentEquals(file.readBytes()))
    }

    @Test
    fun shareProducesACopyNamedLikeTheDocument() {
        open("basic.pdf")
        vm.share()
        awaitMain("share event") { lastEvent<UiEvent.Share>() != null }
        val shared = lastEvent<UiEvent.Share>()!!.file
        assertEquals("basic.pdf", shared.name)
        assertEquals("shared", shared.parentFile!!.name)
        assertTrue(shared.readBytes().contentEquals(file.readBytes()))
    }
}
