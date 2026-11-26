package io.github.legacylive88.simplepdfs

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.legacylive88.simplepdfs.doc.DocumentSource
import io.github.legacylive88.simplepdfs.doc.RecentDocuments
import io.github.legacylive88.simplepdfs.pdfcore.MarkupInfo
import io.github.legacylive88.simplepdfs.pdfcore.NotEditableReason
import io.github.legacylive88.simplepdfs.pdfcore.ParagraphInfo
import io.github.legacylive88.simplepdfs.pdfcore.PdfEditException
import io.github.legacylive88.simplepdfs.pdfcore.PdfPoint
import io.github.legacylive88.simplepdfs.pdfcore.PdfSession
import io.github.legacylive88.simplepdfs.pdfcore.Quad
import io.github.legacylive88.simplepdfs.pdfcore.SaveVerifier
import io.github.legacylive88.simplepdfs.pdfcore.TextSelection
import io.github.legacylive88.simplepdfs.render.PageRasterizer
import io.github.legacylive88.simplepdfs.render.PasswordProtectedException
import io.github.legacylive88.simplepdfs.render.PlatformRasterizer
import io.github.legacylive88.simplepdfs.view.PdfPagesView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

private const val TAG = "SimplePdfs"

enum class Phase { EMPTY, OPENING, READY, FAILED }

/** Where the user is in the two-tap selection gesture. */
enum class SelectMode { OFF, FIRST_POINT, SECOND_POINT }

/** Everything the screen needs to draw itself. */
data class UiState(
    val phase: Phase = Phase.EMPTY,
    val title: String = "",
    val pageCount: Int = 0,
    @param:StringRes val error: Int = 0,
    /** Feature 3: whether the toolbars are shown. */
    val toolsVisible: Boolean = true,
    val selectMode: SelectMode = SelectMode.OFF,
    /** The word under the first tap while waiting for the second. */
    val anchorPage: Int = -1,
    val anchorQuads: List<Quad> = emptyList(),
    val selection: TextSelection? = null,
    /** The engine has finished reading the document, so text tools work. */
    val textReady: Boolean = false,
    /** An edit or a long operation is running. */
    val working: Boolean = false,
    /** A tap is waiting for the engine (it is still loading, or busy). */
    val textBusy: Boolean = false,
    /** The original location cannot be written; edits live only in the app's copy. */
    val editsNotInOriginal: Boolean = false,
    /** Bumped when page sizes were (re)measured. */
    val layoutVersion: Int = 0,
    /** Bumped when highlights changed. */
    val markupVersion: Int = 0,
)

/** One-off things for the screen to do. */
sealed interface UiEvent {
    data class Message(@param:StringRes val text: Int, val argument: String? = null, val long: Boolean = false) : UiEvent
    data class EditParagraph(val paragraph: ParagraphInfo) : UiEvent
    data class CopyToClipboard(val text: String) : UiEvent
    data class Share(val file: File) : UiEvent
    data class PageChanged(val pageIndex: Int) : UiEvent
    /** Result of saving a paragraph, for the editor dialog: null means success. */
    data class ParagraphResult(@param:StringRes val error: Int, val argument: String? = null) : UiEvent
}

/**
 * Owns the open document and implements the app's behaviour. The screen (activity + page view)
 * only forwards input and renders [state].
 *
 * Three threads are involved besides the main thread, each with one job:
 *  - *engine*: everything that touches [PdfSession] (text, highlights, edits, saving),
 *  - *render*: everything that touches the [PageRasterizer],
 *  - I/O for copying the document in and mirroring edits back to where it lives.
 */
class DocumentViewModel(
    app: Application,
    private val rasterizers: PageRasterizer.Factory,
    private val verifier: SaveVerifier?,
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(app), PdfPagesView.PageSource {

    /** Used by the framework: the real renderer and verifier. */
    @Suppress("unused")
    constructor(app: Application) : this(app, PlatformRasterizer.FACTORY, PlatformRasterizer.VERIFIER, Dispatchers.IO)

    private val engineThread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-engine") }.asCoroutineDispatcher()
    private val renderThread: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    val recents = RecentDocuments(app)

    /** The open document; only assigned on the main thread. */
    private class OpenDocument(
        var source: DocumentSource,
        val workDir: File,
        val baseFile: File,
        @Volatile var rasterizer: PageRasterizer,
        val sizes: Array<FloatArray>,
    ) {
        val session = CompletableDeferred<PdfSession?>()

        /** The engine once it has loaded; null before that and when it could not load. */
        @Volatile var loaded: PdfSession? = null
        val markups = ConcurrentHashMap<Int, List<MarkupInfo>>()
        val markupsRequested = HashSet<Int>()
        val overlayScales = ConcurrentHashMap<Int, Float>()
        var writable = false
        var remoteLength = 0L
        val syncLock = Mutex()
        @Volatile var closed = false
    }

    private var doc: OpenDocument? = null
    private var openJob: Job? = null

    /** URI of the open document, for restoring after the process was killed. */
    val currentUri: Uri? get() = doc?.source?.uri

    val displayName: String get() = doc?.source?.displayName ?: ""

    private fun send(event: UiEvent) {
        _events.trySend(event)
    }

    private fun message(@StringRes text: Int, argument: String? = null, long: Boolean = false) =
        send(UiEvent.Message(text, argument, long))

    // ---------------------------------------------------------------------------------------------
    // Opening and closing
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the document at [uri].
     *
     * @param persist ask the system to keep access across restarts (documents chosen in the picker)
     */
    fun open(uri: Uri, persist: Boolean) {
        openJob?.cancel()
        closeDocument()
        val source = DocumentSource(getApplication(), uri)
        _state.value = UiState(phase = Phase.OPENING, title = source.displayName, toolsVisible = _state.value.toolsVisible)

        openJob = viewModelScope.launch {
            val workDir = File(File(getApplication<Application>().cacheDir, "documents"), "doc-" + System.nanoTime())
            var rasterizer: PageRasterizer? = null
            try {
                if (persist) source.persistPermissions()
                val baseFile = File(workDir, "original.pdf")
                val length = withContext(ioDispatcher) {
                    cleanOldWorkDirs(workDir.parentFile)
                    if (!workDir.mkdirs()) throw IOException("Cannot create working directory")
                    source.copyTo(baseFile)
                }
                if (length <= 0L) throw IOException("Empty file")

                val opened = withContext(renderThread) {
                    val r = rasterizers.open(baseFile)
                    if (r.pageCount <= 0) {
                        r.close()
                        throw IOException("No pages")
                    }
                    Pair(r, r.pageSize(0))
                }
                rasterizer = opened.first
                val first = opened.second
                val sizes = Array(rasterizer.pageCount) { floatArrayOf(first[0], first[1]) }
                val writable = withContext(ioDispatcher) { source.isWritable() }

                val d = OpenDocument(source, workDir, baseFile, rasterizer, sizes)
                d.writable = writable
                d.remoteLength = length
                doc = d
                rasterizer = null // owned by the document from here on
                recents.add(uri, source.displayName)
                _state.update {
                    it.copy(phase = Phase.READY, title = source.displayName, pageCount = d.sizes.size, error = 0)
                }
                launch { measurePages(d) }
                launch { loadEngine(d) }
            } catch (e: PasswordProtectedException) {
                fail(workDir, rasterizer, R.string.err_password, source.displayName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + ioDispatcher) { workDir.deleteRecursively() }
                rasterizer?.let { r -> withContext(NonCancellable + renderThread) { r.close() } }
                throw e
            } catch (e: Exception) {
                logFailure("open", e)
                fail(workDir, rasterizer, R.string.err_open_failed, source.displayName)
                recents.remove(uri)
            }
        }
    }

    private suspend fun fail(workDir: File, rasterizer: PageRasterizer?, @StringRes error: Int, title: String) {
        rasterizer?.let { r -> withContext(renderThread) { r.close() } }
        withContext(ioDispatcher) { workDir.deleteRecursively() }
        _state.update { UiState(phase = Phase.FAILED, title = title, error = error, toolsVisible = true) }
    }

    /** Working copies of documents from earlier runs are of no further use. */
    private fun cleanOldWorkDirs(parent: File?) {
        val current = doc?.workDir
        parent?.listFiles()?.forEach { if (it != current) it.deleteRecursively() }
    }

    /** Page sizes are only known once a page has been opened; measure them in the background. */
    private suspend fun measurePages(d: OpenDocument) {
        val n = d.sizes.size
        var index = 1
        while (index < n && !d.closed) {
            val from = index
            val to = minOf(n, from + 24)
            val changed = withContext(renderThread) {
                var any = false
                if (!d.closed) {
                    for (i in from until to) {
                        val s = try {
                            d.rasterizer.pageSize(i)
                        } catch (_: Exception) {
                            continue
                        }
                        if (abs(s[0] - d.sizes[i][0]) > 0.01f || abs(s[1] - d.sizes[i][1]) > 0.01f) {
                            d.sizes[i] = s
                            any = true
                        }
                    }
                }
                any
            }
            index = to
            if (changed && doc === d) _state.update { it.copy(layoutVersion = it.layoutVersion + 1) }
        }
    }

    private suspend fun loadEngine(d: OpenDocument) {
        val session = withContext(engineThread) {
            try {
                PdfSession.open(getApplication(), d.baseFile, File(d.workDir, "work"), verifier)
            } catch (e: Exception) {
                logFailure("text engine", e)
                null
            } catch (e: OutOfMemoryError) {
                logFailure("text engine", e)
                null
            }
        }
        if (d.closed) {
            withContext(engineThread) { session?.close() }
            d.session.complete(null)
            return
        }
        d.loaded = session
        d.session.complete(session)
        if (doc === d && session != null) {
            _state.update { it.copy(textReady = true) }
            warnAboutContentThatIsNotShown(d, session)
        }
    }

    /**
     * A filled-in form that looks empty would mislead its reader. Where the renderer does not
     * draw annotations, say so once when such a document is opened.
     */
    private suspend fun warnAboutContentThatIsNotShown(d: OpenDocument, session: PdfSession) {
        if (d.rasterizer.drawsAnnotations) return
        val pages = withContext(engineThread) {
            try {
                session.pagesWithOtherAnnotations()
            } catch (_: Exception) {
                emptyList() // closed in the meantime
            }
        }
        if (doc === d && pages.isNotEmpty()) message(R.string.msg_annotations_not_shown, long = true)
    }

    /** Closes the document and returns to the start screen. */
    fun closeDocument() {
        val d = doc ?: run {
            if (_state.value.phase != Phase.EMPTY) _state.value = UiState(toolsVisible = _state.value.toolsVisible)
            return
        }
        doc = null
        d.closed = true
        _state.value = UiState(toolsVisible = _state.value.toolsVisible)
        release(d)
    }

    private fun release(d: OpenDocument) {
        // The engine and the renderer are closed on their own threads, after whatever they are
        // doing right now; the working copy is deleted last.
        engineScopeLaunch {
            val session = d.session.await()
            withContext(engineThread) { session?.close() }
            withContext(renderThread) { d.rasterizer.close() }
            withContext(ioDispatcher) { d.workDir.deleteRecursively() }
        }
    }

    private fun engineScopeLaunch(block: suspend () -> Unit) {
        // Not tied to viewModelScope: must also run when the view model itself is going away.
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default + NonCancellable).launch {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        val d = doc
        doc = null
        if (d != null) {
            d.closed = true
            release(d)
        }
        engineScopeLaunch {
            // Let the release above run first on the single-threaded dispatchers, then stop them.
            withContext(engineThread) {}
            withContext(renderThread) {}
            engineThread.close()
            renderThread.close()
        }
        super.onCleared()
    }

    // ---------------------------------------------------------------------------------------------
    // PageSource (for the page view)
    // ---------------------------------------------------------------------------------------------

    override val pageCount: Int get() = doc?.sizes?.size ?: 0

    override fun pageSize(pageIndex: Int): FloatArray = doc?.sizes?.getOrNull(pageIndex) ?: floatArrayOf(612f, 792f)

    override fun render(
        pageIndex: Int, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float,
        done: (Boolean) -> Unit,
    ): PdfPagesView.Cancellable {
        val d = doc
        if (d == null) {
            done(false)
            return PdfPagesView.Cancellable { }
        }
        val job = viewModelScope.launch {
            var ok = false
            try {
                ok = withContext(renderThread) {
                    if (d.closed) false else {
                        d.rasterizer.render(pageIndex, bitmap, scale, offsetX, offsetY)
                        true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) { done(false) }
                throw e
            } catch (_: Exception) {
                ok = false
            }
            done(ok && doc === d)
        }
        return PdfPagesView.Cancellable { job.cancel() }
    }

    override fun markups(pageIndex: Int): List<MarkupInfo> {
        val d = doc ?: return emptyList()
        d.markups[pageIndex]?.let { return it }
        // Not known yet: fetch once, then tell the view to draw again.
        if (_state.value.textReady && d.markupsRequested.add(pageIndex)) {
            viewModelScope.launch {
                val session = d.session.await() ?: return@launch
                val list = withContext(engineThread) {
                    if (d.closed) emptyList() else {
                        coordinateScale(d, session, pageIndex)
                        // Stored here, on the engine thread, so that an edit made in the
                        // meantime (which also runs here) cannot be overwritten by this older view.
                        session.markups(pageIndex).also { d.markups[pageIndex] = it }
                    }
                }
                if (doc === d && list.isNotEmpty()) _state.update { it.copy(markupVersion = it.markupVersion + 1) }
            }
        }
        return emptyList()
    }

    override fun overlayScale(pageIndex: Int): Float = doc?.overlayScales?.get(pageIndex) ?: 1f

    /**
     * Ratio between the renderer's page coordinates and the engine's for a page: 1 unless the
     * two disagree about the page size (a PDF `UserUnit`, for instance). Engine thread only.
     */
    private fun coordinateScale(d: OpenDocument, session: PdfSession, pageIndex: Int): Float {
        d.overlayScales[pageIndex]?.let { return it }
        val engine = session.pageSize(pageIndex)
        val shown = d.sizes[pageIndex]
        val k = if (engine.width > 0f && abs(shown[0] - engine.width) > 1.5f) shown[0] / engine.width else 1f
        d.overlayScales[pageIndex] = k
        return k
    }

    // ---------------------------------------------------------------------------------------------
    // Feature 3: show / hide tools
    // ---------------------------------------------------------------------------------------------

    fun setToolsVisible(visible: Boolean) = _state.update { it.copy(toolsVisible = visible) }

    // ---------------------------------------------------------------------------------------------
    // Feature 5: select, copy, highlight, remove highlight
    // ---------------------------------------------------------------------------------------------

    /** The Select button: starts the two-tap selection, or abandons it. */
    fun toggleSelectMode() {
        val s = _state.value
        if (s.phase != Phase.READY) return
        if (s.selectMode != SelectMode.OFF) {
            _state.update { it.copy(selectMode = SelectMode.OFF, anchorPage = -1, anchorQuads = emptyList()) }
        } else {
            _state.update {
                it.copy(selectMode = SelectMode.FIRST_POINT, selection = null, anchorPage = -1, anchorQuads = emptyList())
            }
        }
    }

    fun clearSelection() = _state.update {
        it.copy(selection = null, selectMode = SelectMode.OFF, anchorPage = -1, anchorQuads = emptyList())
    }

    @Volatile
    private var anchorPoint: PdfPoint? = null

    /**
     * A tap on a page, in the page view's page coordinates.
     *
     * What a tap means depends on where the selection gesture stands, and that is decided
     * when the tap is *processed*, in order, on the engine thread – not when it arrives. A
     * second tap that comes in while the first is still being worked on is therefore still
     * understood as the second tap.
     */
    fun onTap(pageIndex: Int, x: Float, y: Float) {
        val s = _state.value
        if (s.selectMode == SelectMode.OFF) {
            if (s.selection != null) clearSelection()
            return
        }
        withText { d, session ->
            val now = _state.value
            when (now.selectMode) {
                SelectMode.OFF -> {}
                SelectMode.FIRST_POINT -> {
                    val k = coordinateScale(d, session, pageIndex)
                    val point = PdfPoint(x / k, y / k)
                    val word = session.select(pageIndex, point, point)
                    if (word == null) {
                        message(R.string.msg_no_text_on_page)
                    } else {
                        anchorPoint = point
                        update { it.copy(selectMode = SelectMode.SECOND_POINT, anchorPage = pageIndex, anchorQuads = word.quads) }
                    }
                }
                SelectMode.SECOND_POINT -> {
                    val start = anchorPoint
                    if (pageIndex != now.anchorPage || start == null) {
                        message(R.string.msg_select_same_page)
                    } else {
                        val k = coordinateScale(d, session, pageIndex)
                        val selection = session.select(pageIndex, start, PdfPoint(x / k, y / k))
                        // After the second tap the Select button is no longer active.
                        update {
                            it.copy(selectMode = SelectMode.OFF, anchorPage = -1, anchorQuads = emptyList(), selection = selection)
                        }
                    }
                }
            }
        }
    }

    fun copySelection() {
        val sel = _state.value.selection ?: return
        if (sel.text.isEmpty()) {
            message(R.string.msg_copy_not_allowed)
            return
        }
        send(UiEvent.CopyToClipboard(sel.text))
    }

    fun highlightSelection() {
        val sel = _state.value.selection ?: return
        edit(sel.pageIndex, contentChanged = false) { session ->
            session.addHighlight(sel)
            update { it.copy(selection = null) }
        }
    }

    fun removeHighlightFromSelection() {
        val sel = _state.value.selection ?: return
        edit(sel.pageIndex, contentChanged = false) { session ->
            if (session.removeHighlights(sel)) {
                update { it.copy(selection = null) }
                true
            } else {
                message(R.string.msg_no_highlight_here)
                false
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Feature 6: edit a paragraph
    // ---------------------------------------------------------------------------------------------

    /** A double tap on a page. While selecting, it simply counts as a tap. */
    fun onDoubleTap(pageIndex: Int, x: Float, y: Float) {
        val s = _state.value
        if (s.selectMode != SelectMode.OFF) {
            onTap(pageIndex, x, y)
            return
        }
        withText { d, session ->
            val k = coordinateScale(d, session, pageIndex)
            val paragraph = session.paragraphAt(pageIndex, PdfPoint(x / k, y / k))
            when {
                paragraph == null -> message(R.string.msg_no_paragraph)
                !paragraph.editable -> message(reasonText(paragraph.notEditableReason))
                else -> {
                    update { it.copy(selection = null) }
                    send(UiEvent.EditParagraph(paragraph))
                }
            }
        }
    }

    @StringRes
    private fun reasonText(reason: NotEditableReason?): Int = when (reason) {
        NotEditableReason.DOCUMENT_PROTECTED -> R.string.msg_protected
        NotEditableReason.INVISIBLE_TEXT -> R.string.reason_invisible
        NotEditableReason.UNREADABLE_CHARACTERS -> R.string.reason_unreadable
        NotEditableReason.UNSUPPORTED_WRITING_DIRECTION -> R.string.reason_direction
        null -> R.string.msg_edit_failed
    }

    /** Saves new text for a paragraph. The outcome is reported as [UiEvent.ParagraphResult]. */
    fun saveParagraph(paragraph: ParagraphInfo, text: String) {
        edit(paragraph.pageIndex, contentChanged = true, reportTo = { error, arg -> send(UiEvent.ParagraphResult(error, arg)) }) { session ->
            val missing = session.unsupportedCharacters(paragraph, text)
            if (missing.isNotEmpty()) {
                throw PdfEditException(PdfEditException.Reason.UNSUPPORTED_CHARACTERS, missing)
            }
            val result = session.replaceParagraph(paragraph, text)
            when {
                !result.changed -> {}
                result.overflowed -> message(R.string.msg_paragraph_saved_overflow, long = true)
                result.fontScale < 0.999f -> message(R.string.msg_paragraph_saved_smaller, long = true)
                else -> message(R.string.msg_paragraph_saved)
            }
            result.changed
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Running things on the engine
    // ---------------------------------------------------------------------------------------------

    private var textRequests = 0

    /**
     * Runs [block] on the engine thread once the document's text is available. Requests run
     * strictly in the order they were made.
     */
    private fun withText(block: Scope.(OpenDocument, PdfSession) -> Unit) {
        val d = doc ?: return
        textRequests++
        _state.update { it.copy(textBusy = true) }
        // UNDISPATCHED: reach the engine queue immediately, so two requests made one after the
        // other cannot overtake each other.
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                if (!d.session.isCompleted) message(R.string.msg_preparing_text)
                val session = d.session.await()
                if (session == null) {
                    message(R.string.msg_text_unavailable, long = true)
                } else if (doc === d) {
                    withContext(engineThread) {
                        if (!d.closed) Scope(d).block(d, session)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("text", e)
                message(R.string.msg_edit_failed)
            } finally {
                textRequests--
                if (textRequests == 0) _state.update { it.copy(textBusy = false) }
            }
        }
    }

    /** What code running on the engine thread may do to the UI state. */
    private inner class Scope(private val d: OpenDocument) {
        fun update(change: (UiState) -> UiState) {
            if (doc === d) _state.update(change)
        }
    }

    /**
     * Runs an edit, then refreshes what the screen shows and mirrors the document back to where
     * it came from. [block] returns Unit or a Boolean saying whether anything was written.
     */
    private fun edit(
        pageIndex: Int,
        contentChanged: Boolean,
        reportTo: ((Int, String?) -> Unit)? = null,
        block: Scope.(PdfSession) -> Any,
    ) {
        val d = doc
        if (d == null || _state.value.working) {
            // Something else is being saved right now; whoever asked must not be left waiting.
            reportTo?.invoke(R.string.msg_stale, null)
            return
        }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            var error = 0
            var argument: String? = null
            var wrote = false
            try {
                val session = d.session.await()
                if (session == null) {
                    error = R.string.msg_text_unavailable
                } else if (doc === d) {
                    wrote = withContext(engineThread) {
                        val result = Scope(d).block(session)
                        val changed = result != false
                        if (changed) {
                            coordinateScale(d, session, pageIndex)
                            d.markups[pageIndex] = session.markups(pageIndex)
                        }
                        changed
                    }
                    if (wrote) {
                        if (contentChanged) reopenRasterizer(d, session)
                        if (doc === d) {
                            _state.update { it.copy(markupVersion = it.markupVersion + 1) }
                            if (contentChanged) send(UiEvent.PageChanged(pageIndex))
                        }
                        mirrorToSource(d, session)
                    }
                }
            } catch (e: PdfEditException) {
                if (e.reason != PdfEditException.Reason.UNSUPPORTED_CHARACTERS) logFailure("edit (${e.reason})", e)
                when (e.reason) {
                    PdfEditException.Reason.UNSUPPORTED_CHARACTERS -> {
                        error = R.string.msg_unsupported_chars
                        argument = e.message?.substringAfter(": ", e.message ?: "")
                    }
                    PdfEditException.Reason.NOT_ALLOWED -> error = R.string.msg_protected
                    PdfEditException.Reason.NOT_EDITABLE -> error = R.string.msg_edit_failed
                    PdfEditException.Reason.STALE -> error = R.string.msg_stale
                    else -> error = R.string.msg_edit_failed
                }
                // A failed edit rolled the engine back; what the screen shows is still correct,
                // but handles it holds (the selection) are no longer valid.
                if (e.reason != PdfEditException.Reason.UNSUPPORTED_CHARACTERS && doc === d) {
                    _state.update { it.copy(selection = null) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("edit", e)
                error = R.string.msg_edit_failed
            } finally {
                _state.update { it.copy(working = false) }
            }
            if (reportTo != null) reportTo(error, argument) else if (error != 0) message(error, argument, long = true)
        }
    }

    /** After page content changed, the renderer must read the new file. */
    private suspend fun reopenRasterizer(d: OpenDocument, session: PdfSession) {
        val file = withContext(engineThread) { session.currentFile }
        withContext(renderThread) {
            if (d.closed) return@withContext
            val fresh = try {
                rasterizers.open(file)
            } catch (_: Exception) {
                null
            } ?: return@withContext
            val old = d.rasterizer
            d.rasterizer = fresh
            old.close()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // "These edits are saved instantly over the same file"
    // ---------------------------------------------------------------------------------------------

    private suspend fun mirrorToSource(d: OpenDocument, session: PdfSession) {
        if (!d.writable) {
            if (doc === d) _state.update { it.copy(editsNotInOriginal = true) }
            return
        }
        val ok = withContext(ioDispatcher + NonCancellable) {
            d.syncLock.withLock {
                try {
                    // A private snapshot, so a later edit cannot swap the file while it is read.
                    val (snapshot, prefix) = withContext(engineThread) {
                        val copy = File(d.workDir, "outgoing.pdf")
                        session.copyTo(copy)
                        Pair(copy, session.unchangedPrefixLength)
                    }
                    d.source.push(snapshot, prefix, d.remoteLength)
                    d.remoteLength = snapshot.length()
                    snapshot.delete()
                    true
                } catch (e: Exception) {
                    logFailure("write back", e)
                    false
                }
            }
        }
        if (doc !== d) return
        if (ok) {
            _state.update { it.copy(editsNotInOriginal = false) }
        } else {
            d.writable = false
            _state.update { it.copy(editsNotInOriginal = true) }
            message(R.string.msg_not_saved_to_original, long = true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Feature 1: save copy   ·   Feature 2: share
    // ---------------------------------------------------------------------------------------------

    /** Name to suggest for a copy. */
    fun suggestedCopyName(): String {
        val name = displayName.ifBlank { "document.pdf" }
        val stem = name.removeSuffix(".pdf").removeSuffix(".PDF")
        return "$stem (copy).pdf"
    }

    /**
     * Writes the document, with all edits, to [target].
     *
     * @param adopt continue working on [target] from now on (used when the original location
     *   is read-only: edits are then saved over the newly created file)
     */
    fun saveCopyTo(target: Uri, adopt: Boolean) {
        val d = doc ?: return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val destination = DocumentSource(getApplication(), target)
            val length = try {
                val snapshot = snapshot(d, "copy.pdf")
                withContext(ioDispatcher) {
                    try {
                        destination.push(snapshot, 0L, -1L)
                        snapshot.length()
                    } finally {
                        snapshot.delete()
                    }
                }
            } catch (_: Exception) {
                -1L
            }
            _state.update { it.copy(working = false) }
            if (doc !== d) return@launch
            if (length < 0) {
                message(R.string.msg_save_copy_failed, long = true)
                return@launch
            }
            if (adopt) {
                destination.persistPermissions()
                d.syncLock.withLock {
                    d.source = destination
                    d.remoteLength = length
                    d.writable = true
                }
                recents.add(target, destination.displayName)
                _state.update { it.copy(title = destination.displayName, editsNotInOriginal = false) }
            }
            message(R.string.msg_saved_copy)
        }
    }

    fun share() {
        val d = doc ?: return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val file = try {
                val snapshot = snapshot(d, "share.pdf")
                withContext(ioDispatcher) {
                    val dir = File(getApplication<Application>().cacheDir, "shared")
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val out = File(dir, safeFileName(d.source.displayName))
                    if (!snapshot.renameTo(out)) {
                        snapshot.copyTo(out, overwrite = true)
                        snapshot.delete()
                    }
                    out
                }
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(working = false) }
            if (file == null) message(R.string.msg_share_failed) else send(UiEvent.Share(file))
        }
    }

    /** A stable copy of the document as it is now, including every edit. */
    private suspend fun snapshot(d: OpenDocument, name: String): File {
        val target = File(d.workDir, name)
        val session = d.loaded
        if (session != null) {
            withContext(engineThread) { session.copyTo(target) }
        } else {
            // The engine is still loading, so nothing can have been edited yet.
            withContext(ioDispatcher) { d.baseFile.copyTo(target, overwrite = true) }
        }
        return target
    }

    /**
     * Records why something failed, for bug reports. Only the kind of failure is logged, never
     * document names or content.
     */
    private fun logFailure(what: String, e: Throwable) {
        var cause: Throwable? = e
        val chain = StringBuilder()
        var depth = 0
        while (cause != null && depth < 4) {
            if (depth > 0) chain.append(" <- ")
            chain.append(cause.javaClass.simpleName)
            depth++
            cause = cause.cause
        }
        android.util.Log.w(TAG, "$what failed: $chain")
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifBlank { "document" }
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }
}
