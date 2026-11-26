# Architecture

Legacy's Simple PDFs is a native Android app written in Kotlin. There is no WebView and no
JavaScript: pages are drawn by Android's own PDF renderer onto a custom view, and everything
that reads or changes the *content* of a PDF is done by an engine module that has no UI code.

```
┌──────────────────────────── app ────────────────────────────┐
│ MainActivity          one screen: title bar, pages, tools     │
│ DocumentViewModel     all behaviour; owns the open document   │
│ view/PdfPagesView     scroll · zoom · taps · overlays         │
│ render/PageRasterizer pixels (Android PdfRenderer)            │
│ doc/DocumentSource    reads the document, writes edits back   │
└───────────────┬─────────────────────────────────────────────┘
                │ public Kotlin API (PdfSession, data classes)
┌───────────────▼────────────────── pdfcore ───────────────────┐
│ PdfSession            one open document                       │
│ GlyphExtractor        every glyph + where it comes from       │
│ LayoutAnalyzer        glyphs → lines → words → paragraphs     │
│ Markup                highlight annotations                   │
│ ParagraphEditor       content-stream rewrite + typesetting    │
│ IncrementWriter       incremental save                        │
└──────────────────────────────────────────────────────────────┘
        PdfBox-Android (Apache 2.0) · Liberation & DejaVu fonts
```

## Why these choices

| Decision | Reason |
|---|---|
| **Kotlin, native views** instead of ReactJS | The README asked for React but allowed another language "if it would work better", and ruled out wrapper apps. The hard parts here – pixel rendering, gesture handling and byte-level PDF surgery – are all native work on Android; a React Native shell would only add a bridge in front of the same native code. |
| **Two modules** | `pdfcore` knows nothing about screens. That keeps it testable on a plain JVM and means it can later be wrapped as a React Native native module (or reused in another app) without untangling UI code. |
| **Android `PdfRenderer` for pixels** | It is PDFium, maintained by Google and updated with the system. Nothing to bundle, so the APK stays small, and pages look the same as in other apps on the device. |
| **PdfBox-Android for content** | The only mature, permissively licensed (Apache 2.0) library that exposes PDF internals at the level needed to edit text. MuPDF and iText are AGPL. |
| **Edits are incremental updates** | The original bytes of the user's file are never rewritten – see [Saving](#saving). |
| **Every save is verified by a second PDF implementation** | The engine writes with PdfBox; the result is only accepted if Android's PDFium opens it, can draw the changed pages and – after a paragraph edit – draws the page *identically to before* everywhere outside the edited paragraph. |

## The document life cycle

1. **Open.** The app is given a `content://` URI (file picker, "Open with", "Share to"). It
   copies the document into its private cache – PDF libraries need a real, seekable file, and
   many providers only offer streams – and remembers whether the URI can be written.
2. **Show.** `PlatformRasterizer` opens the copy and the first page appears. Sizes of the other
   pages are measured in the background.
3. **Text engine.** In parallel `PdfSession.open` parses the copy. Until it is ready the
   document can be read, zoomed, shared and copied; taps that need text are queued.
4. **Edit.** An edit runs on the engine thread: change in memory → write `original + update`
   to a new file → verify → publish. If any step fails the session reloads the last good file,
   so memory and disk never disagree.
5. **Mirror back.** The app writes the new tail over the original URI (see below).

## Threads

| Thread | Does | Why |
|---|---|---|
| main | UI, state | |
| `pdf-engine` (1) | everything touching `PdfSession` | PdfBox is not thread-safe; one thread also gives edits a strict order |
| `pdf-render` (1) | everything touching `PdfRenderer` | only one page may be open at a time |
| I/O pool | copying in, mirroring back | |

`DocumentViewModel` is the only class that crosses threads. Taps are *processed* on the engine
thread in arrival order, so a second tap that arrives while the first is still being worked on
is still understood as the second tap.

## Coordinates

All engine results are in **page display space**: points (1/72 in), origin at the top-left of
the page as displayed (rotation and crop box applied), y downwards. This is exactly the space
`PdfRenderer` draws in, so the view converts with one scale factor. `PageGeometry` holds the
conversion to and from PDF user space; it is the only place page rotation is handled.

## Reading text (`GlyphExtractor`, `LayoutAnalyzer`)

A PDF page has no lines or paragraphs, only positioned glyphs. The extractor walks the content
(including nested form XObjects) and records for every glyph both its geometry and its *origin*:
which stream, which operator, which glyph within that operator. The origin is what makes real
editing possible later.

Glyphs become lines, words and paragraphs using two signals:

- **content order** – producers write text in reading order, so glyphs that are adjacent in the
  stream and on one baseline form a line, even across wide gaps;
- **geometry** – fragments that are *not* adjacent in the stream are only joined when about a
  word space apart, which keeps columns and table cells apart.

Lines with one rhythm in one column form a block; inside a block paragraphs are split at
indents, list markers and "short last lines" (the previous line stopped although the next word
would have fitted). These are heuristics: see the limits in [FEATURES.md](FEATURES.md).

Text that cannot be seen is ignored: outside the page, or clipped by the bounding box of a form
it is drawn in.

## Highlights (`Markup`)

A highlight is a standard `/Highlight` annotation with `/QuadPoints` and an appearance stream
(multiply blend), so every other viewer shows it and any PDF editor can remove it. On screen
the app draws markup itself on top of the page bitmap, because Android's renderer does not draw
annotations before Android 15.

## Editing a paragraph (`ParagraphEditor`)

This is a real edit, not white paint over old text.

1. Every text-showing operator (`Tj`, `TJ`, `'`, `"`) that drew part of the paragraph is
   rewritten so that the removed glyphs become an equivalent pen movement. Whatever else the
   operator – or anything after it – draws stays exactly where it was.
2. If the text lives in a form XObject, that form (and enclosing forms) is copied first, so
   other pages sharing it keep their text.
3. The new text is wrapped to the paragraph's width and written in a bundled font that is
   embedded as a subset: Liberation Sans/Serif/Mono (metric-compatible with Arial/Helvetica,
   Times and Courier), with DejaVu Sans for characters Liberation lacks. Size, colour,
   alignment (left, centred, right, justified), first-line indent and line spacing are taken
   from the original.
4. The block is tagged (`/LSPEdit BMC … EMC`) so that, when the page is read again, the
   paragraph is put back in its place in reading order although it sits at the end of the
   content stream.
5. Two safety nets, either of which rejects the edit and rolls it back:
   - *inside the engine:* the page is extracted again and every glyph that was not part of the
     paragraph must still be there, in order, in the same place;
   - *outside the engine* (`RenderComparison`, in the app): Android's renderer draws the page
     before and after, and outside the edited area the two pictures must be the same. This does
     not depend on the engine being right about anything – it also catches a lost image, a
     shifted line or a clipping change that text extraction would never notice.

A bundled font is used because PDFs normally embed only the glyphs they use; the original font
simply cannot draw newly typed characters.

## Saving

`IncrementWriter` appends a PDF *incremental update*: the original file byte for byte, then
only the objects that changed. All edits of a session go into **one** update that is rewritten
on each save, so editing the same paragraph five times does not leave five copies behind.

Two defects of PdfBox-Android's writer are corrected in this subclass (both found by checking
its output with qpdf): the trailer `/Size` could shrink below the document's object count, and
cross-reference streams lacked the entry for themselves.

A side effect worth knowing: because updates are only ever appended, **the document as it was
before the app touched it is still inside the file**. Cutting the file off at its original
length gives the original back, and PDF tools that understand revisions can do the same.

`DocumentSource.push` then makes the user's file identical to the engine's:

- if the provider gives a seekable descriptor and the remote file still has the expected
  length, **only the tail** after the unchanged original is written – an interrupted save
  cannot damage the original document;
- otherwise the whole file is rewritten (`wt`, then `rwt`, and plain `w` only when the new
  content is not shorter);
- if the location cannot be written at all (a mail attachment, say) the edit stays in the
  app's copy, a banner says so, and "Save as…" makes a new file the document's home.

## Where a community feature would attach

Deferred, but the seams exist: `PdfSession.findText` and `TextSelection` already describe
"a passage in a document" independent of the UI, and highlights are plain annotations. Shared
highlights or comments would be a new module talking to `DocumentViewModel`; nothing in
`pdfcore` would need to know about accounts or networking. The app currently requests **no
permissions** and never goes online, and that should stay true unless a community feature is
deliberately added.
