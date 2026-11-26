package io.github.legacylive88.simplepdfs.pdfcore

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.github.legacylive88.simplepdfs.pdfcore.internal.BundledFontMapper
import io.github.legacylive88.simplepdfs.pdfcore.internal.FontBook
import io.github.legacylive88.simplepdfs.pdfcore.internal.Glyph
import io.github.legacylive88.simplepdfs.pdfcore.internal.IncrementWriter
import io.github.legacylive88.simplepdfs.pdfcore.internal.Markup
import io.github.legacylive88.simplepdfs.pdfcore.internal.PageGeometry
import io.github.legacylive88.simplepdfs.pdfcore.internal.PageText
import io.github.legacylive88.simplepdfs.pdfcore.internal.ParagraphEditor
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * An open PDF document: text geometry, highlights and paragraph editing.
 *
 * ## Threading
 * A session is **not** thread-safe. Call every method from one thread (the app uses a single
 * background thread per document).
 *
 * ## Files
 * The session never touches the file it was opened from ([originalFile]). Each successful edit
 * writes a complete new file – the original bytes followed by one incremental update holding
 * *all* changes made so far – and publishes it as [currentFile]. Because the leading
 * [unchangedPrefixLength] bytes never change, a caller that mirrors the document somewhere else
 * (the user's actual file) only has to rewrite the tail.
 *
 * ## Failure behaviour
 * Every edit is all-or-nothing. If anything goes wrong, including the written file failing
 * verification, the session reloads the last good state and throws [PdfEditException].
 */
class PdfSession private constructor(
    private var doc: PDDocument,
    private var baseFile: File,
    private val workDir: File,
    private val verifier: SaveVerifier?,
    private val fonts: FontBook,
) : Closeable {

    private val editedFile = File(workDir, "edited.pdf")
    private val dirty = LinkedHashSet<COSDictionary>()
    private var revisions = IntArray(doc.numberOfPages)
    private var generation = 0
    private val textCache = object : LinkedHashMap<Int, PageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?): Boolean = size > TEXT_CACHE_PAGES
    }
    private var closed = false

    /**
     * `/Size` of the file the document was loaded from. Read once after loading: the in-memory
     * trailer is updated by every save and would otherwise report sizes of earlier saves.
     */
    private var baseTrailerSize = trailerSize(doc)

    /** The file the session was opened from. Never modified. */
    val originalFile: File = baseFile

    /** The file that holds the document as of the last successful edit. */
    var currentFile: File = baseFile
        private set

    /**
     * Number of leading bytes of [currentFile] that are guaranteed identical to every earlier
     * value of [currentFile] in this session.
     */
    var unchangedPrefixLength: Long = baseFile.length()
        private set

    val pageCount: Int get() = doc.numberOfPages

    val capabilities: DocumentCapabilities = run {
        val encrypted = doc.isEncrypted
        val canExtract = try {
            doc.currentAccessPermission.canExtractContent()
        } catch (_: Exception) {
            true
        }
        // Writing back into an encrypted file is deliberately not attempted: getting it subtly
        // wrong would leave the user with a file nothing can open.
        DocumentCapabilities(
            encrypted = encrypted,
            canCopyText = !encrypted || canExtract,
            canAnnotate = !encrypted,
            canEditContent = !encrypted,
        )
    }

    // --- reading ----------------------------------------------------------------------------------

    private fun checkOpen() {
        check(!closed) { "Session is closed" }
    }

    private fun checkPage(pageIndex: Int) {
        require(pageIndex in 0 until pageCount) { "No page $pageIndex" }
    }

    /** Size of the page as displayed, in points. */
    fun pageSize(pageIndex: Int): PageSize {
        checkOpen(); checkPage(pageIndex)
        val g = PageGeometry.of(doc.getPage(pageIndex))
        return PageSize(g.displayWidth.toFloat(), g.displayHeight.toFloat())
    }

    private fun pageText(pageIndex: Int): PageText {
        val cached = textCache[pageIndex]
        if (cached != null && cached.revision == revisions[pageIndex]) return cached
        val page = doc.getPage(pageIndex)
        val fresh = try {
            PageText.extract(page, pageIndex, revisions[pageIndex])
        } catch (e: Exception) {
            // A page whose content cannot be parsed simply has no selectable text.
            PageText.empty(page, pageIndex, revisions[pageIndex])
        }
        textCache[pageIndex] = fresh
        return fresh
    }

    /** True when the page has text that can be selected. */
    fun hasText(pageIndex: Int): Boolean {
        checkOpen(); checkPage(pageIndex)
        return pageText(pageIndex).hasText
    }

    /** All text of a page in reading order, paragraphs separated by line breaks. */
    fun pageTextContent(pageIndex: Int): String {
        checkOpen(); checkPage(pageIndex)
        return pageText(pageIndex).layout.paragraphs.joinToString("\n") { it.text }
    }

    /** Every word on the page with its box, in reading order. Used by the tests. */
    internal fun wordBoxes(pageIndex: Int): List<Pair<String, Quad>> {
        checkOpen(); checkPage(pageIndex)
        val pt = pageText(pageIndex)
        val out = ArrayList<Pair<String, Quad>>()
        for (p in pt.layout.paragraphs) for (line in p.lines) for (w in line.words) {
            val a = line.glyphs[w.first]
            val b = line.glyphs[w.last]
            out.add(w.text to line.frame.quad(line.glyphU(a), line.glyphU(b) + b.width, line.top, line.bottom))
        }
        return out
    }

    /** Paragraph texts of a page in reading order. Used by the tests. */
    internal fun paragraphTexts(pageIndex: Int): List<String> {
        checkOpen(); checkPage(pageIndex)
        return pageText(pageIndex).layout.paragraphs.map { it.text }
    }

    /** Human readable description of how a page was segmented; for tests and diagnostics only. */
    fun debugDump(pageIndex: Int): String {
        checkOpen(); checkPage(pageIndex)
        val pt = pageText(pageIndex)
        val sb = StringBuilder()
        for (p in pt.layout.paragraphs) {
            val reason = pt.notEditableReason(p)
            sb.append(String.format(java.util.Locale.US, "P%d [%d lines, %.1fpt, x %.1f..%.1f, y %.1f..%.1f, dir %d]%s\n",
                p.index, p.lines.size, p.lines[0].size, p.left, p.right, p.top, p.bottom, p.bucket,
                if (reason != null) " not editable: $reason" else ""))
            for (line in p.lines) sb.append("    | ").append(line.text).append('\n')
        }
        return sb.toString()
    }

    /** Highlights and other text markup on the page, for drawing on top of the rendered page. */
    fun markups(pageIndex: Int): List<MarkupInfo> {
        checkOpen(); checkPage(pageIndex)
        val page = doc.getPage(pageIndex)
        return try {
            Markup.read(page, pageIndex, PageGeometry.of(page))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Selects the text between two points on a page (whole words, in reading order).
     * Returns null when the page has no text.
     */
    fun select(pageIndex: Int, a: PdfPoint, b: PdfPoint): TextSelection? {
        checkOpen(); checkPage(pageIndex)
        val pt = pageText(pageIndex)
        val range = pt.rangeBetween(a.x, a.y, b.x, b.y) ?: return null
        val text = if (capabilities.canCopyText) pt.textFor(range) else ""
        return TextSelection(pageIndex, text, pt.quadsFor(range), pt.revision, range.startSeq, range.endSeq)
    }

    /**
     * Finds a word or phrase on a page. Whole words are matched, in reading order; a phrase
     * may continue on the next line. Each hit is returned as a selection, so it can be shown,
     * copied or highlighted like one made by tapping.
     */
    fun findText(pageIndex: Int, query: String, ignoreCase: Boolean = true): List<TextSelection> {
        checkOpen(); checkPage(pageIndex)
        val pt = pageText(pageIndex)
        return pt.find(query, ignoreCase).map { range ->
            val text = if (capabilities.canCopyText) pt.textFor(range) else ""
            TextSelection(pageIndex, text, pt.quadsFor(range), pt.revision, range.startSeq, range.endSeq)
        }
    }

    /** The paragraph under a point, or null when the point is not on text. */
    fun paragraphAt(pageIndex: Int, point: PdfPoint): ParagraphInfo? {
        checkOpen(); checkPage(pageIndex)
        val pt = pageText(pageIndex)
        val p = pt.paragraphAt(point.x, point.y) ?: return null
        val reason = if (!capabilities.canEditContent) NotEditableReason.DOCUMENT_PROTECTED else pt.notEditableReason(p)
        val plan = ParagraphEditor(doc, doc.getPage(pageIndex), pt, fonts).plan(p)
        return ParagraphInfo(
            pageIndex = pageIndex,
            text = p.text,
            quads = pt.lineQuads(p),
            editable = reason == null,
            notEditableReason = reason,
            replacementFontName = plan.style.label,
            fontSize = plan.fontSize,
            pageRevision = pt.revision,
            paragraphIndex = p.index,
        )
    }

    /**
     * Characters of [text] that cannot be written into [paragraph] because none of the bundled
     * fonts contains them. Empty when the text is fine.
     */
    fun unsupportedCharacters(paragraph: ParagraphInfo, text: String): String {
        checkOpen()
        val pt = pageText(paragraph.pageIndex)
        if (pt.revision != paragraph.pageRevision) return ""
        val p = pt.layout.paragraphs.getOrNull(paragraph.paragraphIndex) ?: return ""
        val plan = ParagraphEditor(doc, doc.getPage(paragraph.pageIndex), pt, fonts).plan(p)
        return fonts.unsupported(plan.style, text)
    }

    // --- editing ----------------------------------------------------------------------------------

    /**
     * Highlights the selected text and saves. Any highlight already under the selection is
     * replaced, so highlighting the same text twice does not darken it.
     */
    @Throws(PdfEditException::class)
    fun addHighlight(selection: TextSelection, colorRgb: Int = Markup.DEFAULT_HIGHLIGHT_RGB) {
        checkOpen()
        requireAllowed(capabilities.canAnnotate)
        requireFresh(selection.pageIndex, selection.pageRevision)
        transact {
            val page = doc.getPage(selection.pageIndex)
            val geom = PageGeometry.of(page)
            Markup.removeHighlights(doc, page, geom, selection.quads)?.let { dirty.addAll(it) }
            Markup.addHighlight(doc, page, geom, selection.quads, colorRgb, selection.text.ifEmpty { null })
            dirty.add(page.cosObject)
            Change(Unit, PageChange(selection.pageIndex, pageSize(selection.pageIndex), false, selection.quads))
        }
    }

    /**
     * Removes highlighting from the selected text and saves. Highlights that extend beyond the
     * selection are shortened rather than deleted.
     *
     * @return false when there was no highlight under the selection (nothing was written)
     */
    @Throws(PdfEditException::class)
    fun removeHighlights(selection: TextSelection): Boolean {
        checkOpen()
        requireAllowed(capabilities.canAnnotate)
        requireFresh(selection.pageIndex, selection.pageRevision)
        val page = doc.getPage(selection.pageIndex)
        val geom = PageGeometry.of(page)
        // Dry run first so "nothing to remove" does not cost a save.
        val existing = Markup.read(page, selection.pageIndex, geom).filter { it.kind == MarkupKind.HIGHLIGHT }
        if (existing.none { Markup.subtract(it.quads, selection.quads) != null }) return false
        transact {
            val modified = Markup.removeHighlights(doc, page, geom, selection.quads)
            if (modified != null) dirty.addAll(modified)
            dirty.add(page.cosObject)
            Change(Unit, PageChange(selection.pageIndex, pageSize(selection.pageIndex), false, selection.quads))
        }
        return true
    }

    /**
     * Replaces the text of a paragraph and saves.
     *
     * The paragraph's glyphs are removed from the page content and [newText] is typeset in
     * their place: wrapped to the paragraph's width, in the bundled font named by
     * [ParagraphInfo.replacementFontName], with the original size, colour, alignment and line
     * spacing. Text that is too long is first scaled down (to 60 % at most) and only then
     * allowed to run over what follows; the result reports both.
     */
    @Throws(PdfEditException::class)
    fun replaceParagraph(paragraph: ParagraphInfo, newText: String): ParagraphEditResult {
        checkOpen()
        requireAllowed(capabilities.canEditContent)
        requireFresh(paragraph.pageIndex, paragraph.pageRevision)
        if (!paragraph.editable) {
            throw PdfEditException(PdfEditException.Reason.NOT_EDITABLE, "This paragraph cannot be edited")
        }
        val pageIndex = paragraph.pageIndex
        val before = pageText(pageIndex)
        val p = before.layout.paragraphs.getOrNull(paragraph.paragraphIndex)
            ?: throw PdfEditException(PdfEditException.Reason.STALE, "The page has changed")
        if (normalise(newText) == normalise(p.text)) return ParagraphEditResult(false, 1f, false, p.lines.size)

        val editor = ParagraphEditor(doc, doc.getPage(pageIndex), before, fonts)
        // Checked up front so this common user error never costs a rollback.
        val missing = fonts.unsupported(editor.plan(p).style, newText)
        if (missing.isNotEmpty()) {
            throw PdfEditException(
                PdfEditException.Reason.UNSUPPORTED_CHARACTERS,
                "The built-in fonts cannot draw: $missing",
            )
        }

        val oldArea = before.lineQuads(p)
        return transact {
            val outcome = editor.replace(p, newText)
            val page = doc.getPage(pageIndex)
            dirty.add(page.cosObject)
            revisions[pageIndex]++
            textCache.remove(pageIndex)
            val after = pageText(pageIndex)
            verifyUntouchedText(before, p, after, outcome.originalOperatorCount)
            val area = ArrayList(oldArea)
            boundsOfNewText(after, outcome.originalOperatorCount)?.let { area.add(it) }
            Change(
                ParagraphEditResult(true, outcome.fontScale, outcome.overflowed, outcome.lineCount),
                PageChange(pageIndex, pageSize(pageIndex), true, area),
            )
        }
    }

    /** Box around the text an edit has just added (everything after the original operators). */
    private fun boundsOfNewText(after: PageText, originalOperatorCount: Int): Quad? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (g in after.glyphs) {
            if (g.ctx != 0 || g.op < originalOperatorCount) continue
            // the four corners of the glyph's box: along the baseline, and up/down from it
            val upX = g.dirY
            val upY = -g.dirX
            for (along in floatArrayOf(0f, g.width)) for (rise in floatArrayOf(g.asc, g.desc)) {
                val x = g.ox + g.dirX * along + upX * rise
                val y = g.oy + g.dirY * along + upY * rise
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (right < left) null else Quad(left, top, right, top, right, bottom, left, bottom)
    }

    private fun normalise(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /**
     * Safety net for the content-stream rewrite: every glyph that was *not* part of the edited
     * paragraph must still be there, in the same order, in the same place.
     */
    private fun verifyUntouchedText(
        before: PageText,
        edited: io.github.legacylive88.simplepdfs.pdfcore.internal.TextParagraph,
        after: PageText,
        originalOperatorCount: Int,
    ) {
        val removed = HashSet<Glyph>()
        for (line in edited.lines) removed.addAll(line.glyphs)
        val expected = before.glyphs.filter { it !in removed }
        val actual = after.glyphs.filter { !(it.ctx == 0 && it.op >= originalOperatorCount) }
        if (expected.size != actual.size) {
            throw PdfEditException(
                PdfEditException.Reason.VERIFICATION_FAILED,
                "Edit would have changed other text (${expected.size} glyphs expected, ${actual.size} found)",
            )
        }
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            val tol = 0.05f + 1e-4f * (abs(e.ox) + abs(e.oy))
            if (e.text != a.text || abs(e.ox - a.ox) > tol || abs(e.oy - a.oy) > tol) {
                throw PdfEditException(
                    PdfEditException.Reason.VERIFICATION_FAILED,
                    "Edit would have moved other text (glyph $i '${e.text}')",
                )
            }
        }
    }

    private fun requireAllowed(allowed: Boolean) {
        if (!allowed) throw PdfEditException(PdfEditException.Reason.NOT_ALLOWED, "This document is protected")
    }

    private fun requireFresh(pageIndex: Int, revision: Int) {
        if (pageIndex !in 0 until pageCount || revisions[pageIndex] != revision) {
            throw PdfEditException(PdfEditException.Reason.STALE, "The page has changed since this was selected")
        }
    }

    /** The value an edit returns to its caller, plus what it did for the verifier. */
    private class Change<T>(val value: T, val page: PageChange)

    /** Runs a change, saves it, and rolls everything back if any step fails. */
    private inline fun <T> transact(change: () -> Change<T>): T {
        try {
            val result = change()
            save(result.page)
            return result.value
        } catch (e: PdfEditException) {
            rollback()
            throw e
        } catch (e: Exception) {
            rollback()
            throw PdfEditException(PdfEditException.Reason.FAILED, e.message ?: e.javaClass.simpleName, e)
        } catch (e: OutOfMemoryError) {
            rollback()
            throw PdfEditException(PdfEditException.Reason.FAILED, "Not enough memory for this change", e)
        }
    }

    // --- saving -----------------------------------------------------------------------------------

    private fun save(change: PageChange) {
        val tmp = File(workDir, "edited.tmp")
        tmp.delete()
        val source = RandomAccessBufferedFileInputStream(baseFile)
        try {
            val writer = IncrementWriter(
                BufferedOutputStream(FileOutputStream(tmp), 1 shl 16),
                source,
                LinkedHashSet(dirty),
                doc.document,
                baseTrailerSize,
            )
            try {
                writer.write(doc, null)
            } finally {
                writer.close()
            }
        } finally {
            try {
                source.close()
            } catch (_: IOException) {
            }
        }

        try {
            structuralCheck(tmp)
            verifier?.verify(tmp, currentFile, pageCount, listOf(change))
        } catch (e: Exception) {
            tmp.delete()
            throw PdfEditException(
                PdfEditException.Reason.VERIFICATION_FAILED,
                "The saved file did not pass verification: ${e.message}", e,
            )
        }

        if (!tmp.renameTo(editedFile)) {
            tmp.copyTo(editedFile, overwrite = true)
            tmp.delete()
        }
        currentFile = editedFile
        generation++
    }

    /** Cheap sanity check that does not depend on any PDF parser. */
    private fun structuralCheck(file: File) {
        val base = baseFile.length()
        val length = file.length()
        if (length <= base) throw IOException("Update is empty")
        RandomAccessFile(file, "r").use { raf ->
            val tailSize = minOf(1024L, length).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(length - tailSize)
            raf.readFully(tail)
            val text = String(tail, Charsets.ISO_8859_1)
            val eof = text.lastIndexOf("%%EOF")
            val sx = text.lastIndexOf("startxref")
            if (eof < 0 || sx < 0 || sx > eof) throw IOException("Missing end-of-file marker")
            val offset = text.substring(sx + 9, eof).trim().toLongOrNull() ?: throw IOException("Bad startxref")
            // The new cross-reference section must live in the appended part.
            if (offset < base || offset >= length) throw IOException("Cross-reference offset out of range")
        }
    }

    /** Throws away the in-memory document and reloads the last successfully saved file. */
    private fun rollback() {
        try {
            doc.close()
        } catch (_: Exception) {
        }
        dirty.clear()
        textCache.clear()
        if (currentFile != baseFile) {
            // The saved file becomes the new base; later updates are appended to it.
            val newBase = File(workDir, "base-${generation}-${System.nanoTime()}.pdf")
            if (!currentFile.renameTo(newBase)) currentFile.copyTo(newBase, overwrite = true)
            if (baseFile != originalFile) baseFile.delete()
            baseFile = newBase
            currentFile = newBase
            unchangedPrefixLength = minOf(unchangedPrefixLength, newBase.length())
        }
        doc = load(baseFile, workDir)
        baseTrailerSize = trailerSize(doc)
        // Anything handed out before the rollback refers to objects that no longer exist.
        revisions = IntArray(doc.numberOfPages) { i -> (revisions.getOrNull(i) ?: 0) + 1 }
    }

    /** Copies the current document to [target] (used for "Save copy" and sharing). */
    @Throws(IOException::class)
    fun copyTo(target: File) {
        checkOpen()
        currentFile.copyTo(target, overwrite = true)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            doc.close()
        } catch (_: Exception) {
        }
        fonts.close()
        textCache.clear()
        dirty.clear()
    }

    companion object {
        private const val TEXT_CACHE_PAGES = 6
        private const val MAIN_MEMORY_BYTES = 24L * 1024 * 1024

        /**
         * Pays one-off start-up costs of the PDF library now (it loads the metrics of the 14
         * standard fonts the first time any Type 1 font is met) instead of on the user's first tap.
         */
        private fun warmUp() {
            try {
                PDType1Font.HELVETICA.name
            } catch (_: Throwable) {
            }
        }

        private fun trailerSize(doc: PDDocument): Long =
            maxOf(doc.document.trailer.getLong(COSName.SIZE, 0L), doc.document.highestXRefObjectNumber + 1)

        private fun load(file: File, workDir: File): PDDocument {
            val memory = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(workDir)
            return PDDocument.load(file, memory)
        }

        /**
         * Opens [file].
         *
         * @param file a private copy of the PDF; it is read but never written
         * @param workDir a private directory for the session's working files
         * @param verifier independent check applied to every file the session writes
         * @throws PdfPasswordRequiredException if the file needs a password
         * @throws PdfOpenException if the file cannot be read as a PDF
         */
        @JvmStatic
        @Throws(PdfOpenException::class)
        fun open(context: Context, file: File, workDir: File, verifier: SaveVerifier? = null): PdfSession {
            val app = context.applicationContext
            if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(app)
            BundledFontMapper.install { name -> app.assets.open(name) }
            if (!workDir.isDirectory && !workDir.mkdirs()) throw PdfOpenException("Cannot create working directory")
            val doc = try {
                load(file, workDir)
            } catch (e: InvalidPasswordException) {
                throw PdfPasswordRequiredException(e)
            } catch (e: Exception) {
                throw PdfOpenException(e.message ?: "Not a readable PDF", e)
            } catch (e: OutOfMemoryError) {
                throw PdfOpenException("This PDF is too large to open for editing", e)
            }
            val fonts = FontBook { name -> app.assets.open(name) }
            warmUp()
            return PdfSession(doc, file, workDir, verifier, fonts)
        }
    }
}
