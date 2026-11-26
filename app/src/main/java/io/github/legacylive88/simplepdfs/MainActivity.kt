package io.github.legacylive88.simplepdfs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.legacylive88.simplepdfs.databinding.ActivityMainBinding
import io.github.legacylive88.simplepdfs.databinding.DialogEditParagraphBinding
import io.github.legacylive88.simplepdfs.pdfcore.ParagraphInfo
import io.github.legacylive88.simplepdfs.view.PdfPagesView
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The single screen of the app: the pages, a title bar, a bar of tools at the bottom and,
 * while no document is open, a start screen. All behaviour lives in [DocumentViewModel].
 */
class MainActivity : AppCompatActivity(), PdfPagesView.Listener {

    private lateinit var b: ActivityMainBinding
    private val vm: DocumentViewModel by viewModels()

    private var systemTop = 0
    private var systemBottom = 0
    private var shown: UiState? = null
    private var attachedDocument: Uri? = null
    private var saveAsAdopts = false
    private var editor: EditorDialog? = null

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri, persist = true)
    }

    private val createCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveCopyTo(uri, adopt = saveAsAdopts)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pages.listener = this
        b.pages.setPageBackgroundColor(MaterialColors.getColor(b.root, R.attr.pageBackdrop))
        setUpInsets()
        setUpControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch { vm.events.collect { handle(it) } }
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState == null) {
            handleIntent(intent)
        } else if (vm.state.value.phase == Phase.EMPTY) {
            // The process was killed while a document was open: open it again.
            val uri = savedInstanceState.getString(KEY_URI)?.let(Uri::parse)
            if (uri != null) vm.open(uri, persist = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) vm.open(uri, persist = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        vm.currentUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    override fun onDestroy() {
        editor?.dismiss()
        b.pages.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Set-up
    // ---------------------------------------------------------------------------------------------

    private fun setUpInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTop = bars.top
            systemBottom = bars.bottom
            b.statusScrim.updateLayoutParams { height = bars.top }
            b.navigationScrim.updateLayoutParams { height = bars.bottom }
            b.topBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.bottomArea.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            b.home.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            b.showTools.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = bars.top + dp(8)
                rightMargin = bars.right + dp(8)
            }
            updatePageInsets()
            insets
        }
        // The bars sit on top of the pages; tell the page view how much they cover.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePageInsets() }
        b.topBar.addOnLayoutChangeListener(relayout)
        b.bottomArea.addOnLayoutChangeListener(relayout)
    }

    private fun updatePageInsets() {
        val top = if (b.topBar.isVisible) b.topBar.height else systemTop
        val bottom = if (b.bottomArea.isVisible) b.bottomArea.height else systemBottom
        b.pages.setContentInsets(top, bottom)
    }

    private fun setUpControls() {
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_open -> openPicker()
                R.id.menu_close -> vm.closeDocument()
                R.id.menu_about -> showAbout()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        b.homeOpen.setOnClickListener { openPicker() }

        // Feature 5: select
        b.toolSelect.setOnClickListener { vm.toggleSelectMode() }
        // Feature 4: zoom
        b.toolZoomIn.setOnClickListener { b.pages.zoomIn() }
        b.toolZoomOut.setOnClickListener { b.pages.zoomOut() }
        // Feature 1: save copy
        b.toolSaveCopy.setOnClickListener { askWhereToSave(adopt = false) }
        // Feature 2: share
        b.toolShare.setOnClickListener { vm.share() }
        // Feature 3: show / hide tools
        b.toolHide.setOnClickListener { vm.setToolsVisible(false) }
        b.showTools.setOnClickListener { vm.setToolsVisible(true) }

        b.selCopy.setOnClickListener { vm.copySelection() }
        b.selHighlight.setOnClickListener { vm.highlightSelection() }
        b.selRemoveHighlight.setOnClickListener { vm.removeHighlightFromSelection() }
        b.selDone.setOnClickListener { vm.clearSelection() }

        b.bannerSaveAs.setOnClickListener { askWhereToSave(adopt = true) }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val s = vm.state.value
            when {
                s.selectMode != SelectMode.OFF || s.selection != null -> vm.clearSelection()
                !s.toolsVisible -> vm.setToolsVisible(true)
            }
        }
    }

    private fun openPicker() {
        try {
            pickDocument.launch(arrayOf("application/pdf"))
        } catch (_: ActivityNotFoundException) {
            snack(getString(R.string.msg_no_app_for_action), true)
        }
    }

    private fun askWhereToSave(adopt: Boolean) {
        saveAsAdopts = adopt
        try {
            createCopy.launch(if (adopt) vm.displayName.ifBlank { "document.pdf" } else vm.suggestedCopyName())
        } catch (_: ActivityNotFoundException) {
            snack(getString(R.string.msg_no_app_for_action), true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Page view callbacks
    // ---------------------------------------------------------------------------------------------

    override fun onTap(pageIndex: Int, x: Float, y: Float) = vm.onTap(pageIndex, x, y)

    override fun onDoubleTap(pageIndex: Int, x: Float, y: Float) = vm.onDoubleTap(pageIndex, x, y)

    override fun onViewportChanged(firstVisiblePage: Int, zoom: Float) {
        updateSubtitle()
        b.toolZoomIn.isEnabled = b.pages.canZoomIn
        b.toolZoomOut.isEnabled = b.pages.canZoomOut
    }

    private fun updateSubtitle() {
        val s = vm.state.value
        val page = b.pages.firstVisiblePage()
        b.toolbar.subtitle = if (s.phase == Phase.READY && page >= 0) {
            getString(R.string.page_indicator, page + 1, s.pageCount)
        } else {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State -> screen
    // ---------------------------------------------------------------------------------------------

    private fun render(s: UiState) {
        val old = shown
        shown = s
        val ready = s.phase == Phase.READY

        // Document attached to / detached from the page view.
        val uri = if (ready) vm.currentUri else null
        if (uri != attachedDocument) {
            attachedDocument = uri
            b.pages.setSource(if (ready) vm else null)
        } else if (ready && old != null) {
            if (old.layoutVersion != s.layoutVersion) b.pages.onPageSizesChanged()
            if (old.markupVersion != s.markupVersion) b.pages.onMarkupsChanged()
        }
        b.pages.isVisible = ready
        b.home.isVisible = !ready

        // Start screen
        if (!ready) {
            b.homeMessage.text = when {
                s.phase == Phase.FAILED && s.error != 0 -> getString(s.error)
                else -> getString(R.string.home_tagline)
            }
            b.homeMessage.setTextColor(
                MaterialColors.getColor(
                    b.root,
                    if (s.phase == Phase.FAILED) androidx.appcompat.R.attr.colorError
                    else com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
            b.homeOpen.isEnabled = s.phase != Phase.OPENING
            if (old == null || old.phase != s.phase) showRecents()
        }

        // Title bar
        b.toolbar.title = if (s.title.isNotEmpty()) s.title else getString(R.string.app_name)
        b.toolbar.menu.findItem(R.id.menu_close)?.isVisible = ready
        b.progress.isVisible = s.working || s.textBusy || s.phase == Phase.OPENING
        b.banner.isVisible = ready && s.editsNotInOriginal
        updateSubtitle()

        // Feature 3: tools shown or hidden. Without a document the bars always show.
        val tools = s.toolsVisible || !ready
        b.topBar.isVisible = tools
        b.bottomArea.isVisible = tools && ready
        b.showTools.isVisible = !tools
        b.navigationScrim.isVisible = ready

        // Selection: instructions while picking, actions once something is selected.
        val selecting = s.selectMode != SelectMode.OFF
        b.hint.isVisible = selecting
        if (selecting) {
            b.hint.setText(if (s.selectMode == SelectMode.FIRST_POINT) R.string.hint_select_first else R.string.hint_select_second)
        }
        b.selectionBar.isVisible = s.selection != null
        b.toolBar.isVisible = s.selection == null
        markActive(b.toolSelect, selecting)
        b.pages.immediateTaps = selecting
        b.pages.setSelectionAnchor(s.anchorPage, s.anchorQuads)
        val sel = s.selection
        if (sel != null) b.pages.setSelection(sel.pageIndex, sel.quads) else b.pages.setSelection(-1, emptyList())
        // A selection must stay reachable even if the tools were hidden.
        if (ready && !s.toolsVisible && (selecting || sel != null)) vm.setToolsVisible(true)

        b.toolZoomIn.isEnabled = b.pages.canZoomIn
        b.toolZoomOut.isEnabled = b.pages.canZoomOut
        backCallback.isEnabled = ready && (selecting || sel != null || !s.toolsVisible)

        b.root.post { updatePageInsets() }
    }

    /** Shows a tool as switched on (the Select button while a selection is being made). */
    private fun markActive(button: com.google.android.material.button.MaterialButton, active: Boolean) {
        val container = MaterialColors.getColor(button, com.google.android.material.R.attr.colorSecondaryContainer)
        val onContainer = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val normal = MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurfaceVariant)
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) container else 0)
        val content = android.content.res.ColorStateList.valueOf(if (active) onContainer else normal)
        button.iconTint = content
        button.setTextColor(content)
        button.isSelected = active
    }

    private fun showRecents() {
        val entries = vm.recents.list()
        b.recentTitle.isVisible = entries.isNotEmpty()
        b.recentList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_recent, b.recentList, false) as TextView
            row.text = entry.name
            row.setOnClickListener { vm.open(entry.uri, persist = false) }
            b.recentList.addView(row)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------

    private fun handle(event: UiEvent) {
        when (event) {
            is UiEvent.Message -> snack(
                if (event.argument != null) getString(event.text, event.argument) else getString(event.text),
                event.long,
            )
            is UiEvent.CopyToClipboard -> copy(event.text)
            is UiEvent.Share -> share(event.file)
            is UiEvent.PageChanged -> b.pages.invalidatePage(event.pageIndex)
            is UiEvent.EditParagraph -> {
                editor?.dismiss()
                editor = EditorDialog(event.paragraph).also { it.show() }
            }
            is UiEvent.ParagraphResult -> editor?.onResult(event)
        }
    }

    private fun snack(text: String, long: Boolean) {
        val bar = Snackbar.make(b.root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        if (b.bottomArea.isVisible) bar.anchorView = b.bottomArea
        bar.show()
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name_short), text))
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snack(getString(R.string.msg_copied), false)
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.action_share)))
        } catch (_: Exception) {
            snack(getString(R.string.msg_share_failed), true)
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, getString(R.string.app_name), BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------------------------------
    // Feature 6: the paragraph editor
    // ---------------------------------------------------------------------------------------------

    private inner class EditorDialog(private val paragraph: ParagraphInfo) {
        private val views = DialogEditParagraphBinding.inflate(layoutInflater)
        private val dialog: AlertDialog

        init {
            views.editText.setText(paragraph.text)
            views.editText.setSelection(paragraph.text.length)
            val size = String.format(Locale.getDefault(), "%.1f", paragraph.fontSize).removeSuffix(".0").removeSuffix(",0")
            views.editNote.text = getString(R.string.dialog_edit_note, paragraph.replacementFontName, size)
            dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.dialog_edit_title)
                .setView(views.root)
                .setPositiveButton(R.string.action_save, null) // set below so it does not close the dialog itself
                .setNegativeButton(R.string.action_cancel, null)
                .create()
            dialog.setOnDismissListener { if (editor === this) editor = null }
        }

        fun show() {
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
            views.editText.requestFocus()
        }

        private fun save() {
            views.editLayout.error = null
            setBusy(true)
            vm.saveParagraph(paragraph, views.editText.text?.toString().orEmpty())
        }

        /** Called with the outcome of the save: closes on success, shows the problem otherwise. */
        fun onResult(result: UiEvent.ParagraphResult) {
            setBusy(false)
            if (result.error == 0) {
                dialog.dismiss()
                return
            }
            val text = if (result.argument != null) getString(result.error, result.argument) else getString(result.error)
            if (result.error == R.string.msg_unsupported_chars) {
                // The user can fix this one by changing the text.
                views.editLayout.error = text
            } else {
                dialog.dismiss()
                snack(text, true)
            }
        }

        private fun setBusy(busy: Boolean) {
            views.editProgress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            views.editText.isEnabled = !busy
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    private companion object {
        const val KEY_URI = "document_uri"
    }
}
