package io.github.legacylive88.simplepdfs.pdfcore

/**
 * Public data model of the PDF engine.
 *
 * ## Coordinate space
 *
 * Everything the engine exposes is expressed in **page display space**:
 *  - units are PDF points (1/72 inch),
 *  - the origin is the top-left corner of the page *as it is displayed* (the page's
 *    `/Rotate` and `/CropBox` are already applied),
 *  - x grows to the right and y grows downwards.
 *
 * This is the same space Android's `PdfRenderer` renders into, so a UI can map between
 * screen pixels and engine coordinates with a single scale factor.
 */

/** A point in page display space. */
data class PdfPoint(val x: Float, val y: Float)

/** Size of a page in display space (points). */
data class PageSize(val width: Float, val height: Float)

/**
 * A four-cornered box in page display space. For upright text this is an axis-aligned
 * rectangle; for rotated text it is the rotated rectangle.
 *
 * Corner order follows the text direction: (x1,y1) start-top, (x2,y2) end-top,
 * (x3,y3) end-bottom, (x4,y4) start-bottom.
 */
data class Quad(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val x3: Float, val y3: Float,
    val x4: Float, val y4: Float,
) {
    val left: Float get() = minOf(minOf(x1, x2), minOf(x3, x4))
    val right: Float get() = maxOf(maxOf(x1, x2), maxOf(x3, x4))
    val top: Float get() = minOf(minOf(y1, y2), minOf(y3, y4))
    val bottom: Float get() = maxOf(maxOf(y1, y2), maxOf(y3, y4))

    /** True when [x],[y] lies inside this quad (convex polygon test). */
    fun contains(x: Float, y: Float): Boolean {
        val xs = floatArrayOf(x1, x2, x3, x4)
        val ys = floatArrayOf(y1, y2, y3, y4)
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i])
            if (cross != 0f) {
                val s = if (cross > 0f) 1 else -1
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }
}

/**
 * A range of text on one page, produced by [PdfSession.select].
 *
 * @property text the selected text, ready for the clipboard
 * @property quads one box per selected line (or line fragment), in display space
 */
class TextSelection internal constructor(
    val pageIndex: Int,
    val text: String,
    val quads: List<Quad>,
    internal val pageRevision: Int,
    internal val startSeq: Int,
    internal val endSeq: Int,
)

/** Why a paragraph cannot be edited. */
enum class NotEditableReason {
    /** The document is encrypted or its author has disallowed modification. */
    DOCUMENT_PROTECTED,

    /** The text is invisible (typically the OCR layer of a scanned page). */
    INVISIBLE_TEXT,

    /** The PDF does not say which characters some glyphs are, so they cannot be shown for editing. */
    UNREADABLE_CHARACTERS,

    /** Right-to-left or vertically written text; re-typesetting is not supported yet. */
    UNSUPPORTED_WRITING_DIRECTION,
}

/**
 * A paragraph found on a page, produced by [PdfSession.paragraphAt].
 *
 * @property text the paragraph as one flowing string (line breaks removed)
 * @property quads one box per line, in display space
 * @property editable whether [PdfSession.replaceParagraph] can be used on it
 * @property replacementFontName human readable name of the bundled font the paragraph will be
 *   re-typeset in when edited
 */
class ParagraphInfo internal constructor(
    val pageIndex: Int,
    val text: String,
    val quads: List<Quad>,
    val editable: Boolean,
    val notEditableReason: NotEditableReason?,
    val replacementFontName: String,
    val fontSize: Float,
    internal val pageRevision: Int,
    internal val paragraphIndex: Int,
) {
    val left: Float get() = quads.minOf { it.left }
    val right: Float get() = quads.maxOf { it.right }
    val top: Float get() = quads.minOf { it.top }
    val bottom: Float get() = quads.maxOf { it.bottom }
}

/** Kind of text-markup annotation. Only [HIGHLIGHT] is created or changed by this app. */
enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

/**
 * A text-markup annotation on a page (created by this app or any other PDF app).
 *
 * @property colorRgb 0xRRGGBB
 * @property opacity 0..1
 */
class MarkupInfo internal constructor(
    val pageIndex: Int,
    val kind: MarkupKind,
    val colorRgb: Int,
    val opacity: Float,
    val quads: List<Quad>,
)

/** Result of [PdfSession.replaceParagraph]. */
class ParagraphEditResult internal constructor(
    /** False when the text was identical and nothing was written. */
    val changed: Boolean,
    /** Font scale that was applied to make the new text fit (1.0 = original size). */
    val fontScale: Float,
    /** True when the text did not fit even at the smallest allowed size and may overlap what follows. */
    val overflowed: Boolean,
    /** Number of lines the new text was laid out in. */
    val lineCount: Int,
)

/** What the document allows, derived from encryption and permission flags. */
class DocumentCapabilities internal constructor(
    /** The file is encrypted (even if it opened without a password). */
    val encrypted: Boolean,
    /** Text may be extracted (copied). */
    val canCopyText: Boolean,
    /** Highlights may be added/removed and written back. */
    val canAnnotate: Boolean,
    /** Page content (paragraph text) may be changed and written back. */
    val canEditContent: Boolean,
)

/** Thrown when the engine cannot open a document. */
open class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the document needs a password to be opened. */
class PdfPasswordRequiredException(cause: Throwable? = null) :
    PdfOpenException("This PDF is password protected", cause)

/**
 * Thrown when an edit could not be applied. The session has been rolled back to the last
 * successfully saved state, so the document on disk and in memory are unchanged.
 */
class PdfEditException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** The selection/paragraph belongs to an older version of the page. Ask again. */
        STALE,
        /** The document forbids this change. */
        NOT_ALLOWED,
        /** The new text contains characters the bundled fonts cannot draw. */
        UNSUPPORTED_CHARACTERS,
        /** The paragraph cannot be edited (see [ParagraphInfo.notEditableReason]). */
        NOT_EDITABLE,
        /** The change was applied in memory but the result failed verification and was discarded. */
        VERIFICATION_FAILED,
        /** Anything else (I/O, malformed PDF structures…). */
        FAILED,
    }
}

/**
 * What an edit did to one page, as told to a [SaveVerifier].
 *
 * @property contentChanged true when the page's own content was rewritten (a paragraph edit);
 *   false when only annotations changed (highlights), which never alters how page content renders
 * @property area where the change is, in display space: the old and the new text of an edited
 *   paragraph, or the selection of a highlight. Everything outside it must look exactly as before.
 */
class PageChange(
    val pageIndex: Int,
    /** Size of the page in the same display space as [area]. */
    val pageSize: PageSize,
    val contentChanged: Boolean,
    val area: List<Quad>,
)

/**
 * Checks a freshly written file before the engine accepts it as the new document state.
 * The app supplies an implementation backed by an independent PDF implementation (Android's
 * PdfRenderer), so an edit is only ever kept if a second implementation can open the result
 * and agrees that nothing outside the edited area changed.
 */
fun interface SaveVerifier {
    /**
     * @param candidate the newly written file
     * @param previous the document as it was before this edit
     * @param expectedPageCount number of pages the document must have
     * @param changes what was changed
     * @throws Exception if the candidate is not acceptable
     */
    @Throws(Exception::class)
    fun verify(candidate: java.io.File, previous: java.io.File, expectedPageCount: Int, changes: List<PageChange>)
}
