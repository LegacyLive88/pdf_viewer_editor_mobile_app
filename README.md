# Legacy's Simple PDFs

A simple, free PDF app for Android that views PDFs and has a few tricks up its sleeve.

It is a **native Android app** (Kotlin) – not a web page in a wrapper. Pages are drawn by
Android's own PDF renderer; text selection, highlighting and paragraph editing are done by an
engine module in this repository. It asks for no permissions and never goes online.

## What it does

| # | Feature | In short |
|---|---|---|
| 1 | **Save copy** | Writes the document, with your edits, to a place you choose |
| 2 | **Share** | Sends the document to another app |
| 3 | **Show / hide tools** | Hides the bars for reading; a small button brings them back |
| 4 | **Zoom in / out** | Buttons and pinch, 0.5× to 8×, sharp at every level |
| 5 | **Select → copy / highlight / remove highlight** | Tap *Select*, tap the first word, tap the last word on the same page. Then copy the text, highlight it, or remove highlighting from it |
| 6 | **Edit a paragraph** | With Select off, double-tap a paragraph to edit its text in a small editor |

Highlights and paragraph edits (5 and 6) are **saved instantly over the same file**. Highlights
are standard PDF annotations, so other PDF apps show them too. Paragraph edits really replace
the text in the PDF – the old text is removed, not covered up.

## Things worth knowing

- **Edited paragraphs change font.** They are re-set in Liberation Sans, Serif or Mono
  (whichever matches the original; same character widths as Arial, Times New Roman and Courier
  New), because a PDF's own fonts usually contain only the characters already in the document.
  Size, colour, alignment and line spacing are kept; mixed bold/italic *inside* a paragraph is
  not.
- **Double tap edits, so it does not zoom.** Use the buttons or pinch.
- **Whole words are selected**, and a tap near text counts as a tap on it.
- **Scanned pages** have no text to select or edit unless they carry an OCR text layer; that
  layer can be selected, copied and highlighted, but not edited.
- **Read-only files** (for example straight from an e-mail): edits are kept in the app and a
  banner offers "Save as…" so they can be stored in a file of your own.
- **Protected PDFs:** password-protected files cannot be opened yet; encrypted files that open
  without a password are view-only.
- **Older Android versions** (14 and below) do not show filled-in form fields, stamps or
  sticky notes, because Android's built-in renderer does not draw them there. The app says so
  when such a PDF is opened. Highlights, underlines and strike-outs are always shown.
- There is **no undo**: saving is instant.

The reasoning behind each of these is in [docs/FEATURES.md](docs/FEATURES.md).

## Install

Requires Android 7.0 or newer. Copy the APK to the phone, tap it, and allow installing from
that source when Android asks. See [docs/BUILD_AND_RELEASE.md](docs/BUILD_AND_RELEASE.md).

## Build

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :pdfcore:testDebugUnitTest :app:testDebugUnitTest
```

Needs JDK 21 and the Android SDK (platform 36). Everything else is downloaded by Gradle.

## Documentation

| Document | Contents |
|---|---|
| [docs/FEATURES.md](docs/FEATURES.md) | Each feature: decisions, assumptions, what should be tested, what is tested |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the app and the PDF engine are put together, and why |
| [docs/TESTING.md](docs/TESTING.md) | The four test layers and how to run them |
| [docs/BUILD_AND_RELEASE.md](docs/BUILD_AND_RELEASE.md) | Building, signing, CI, releasing, third-party licences |

## Layout of the repository

```
app/        the Android app: screen, page view, document handling
pdfcore/    the PDF engine (no UI): text, selection, highlights, paragraph editing, saving
tools/      fixture generator and the cross-validation script used by the tests
docs/       documentation
```

## Original specification

> A simple free pdf editor that just views pdfs and has a few tricks up its sleeve. It should be
> built on ReactJS and compiled for Android phones. It has 6 features:
> 1. Save copy
> 2. Share
> 3. Show/hide tools
> 4. Zoom in/out
> 5. Select-highlight (click select button and click somewhere on the page then click somewhere
>    else on the current page and then the text between the two points is selected, one can then
>    copy that text or highlight it or remove highlights -- after second click the select button
>    is no longer active)*
> 6. (When select is off), double clicking on a paragraph opens a simple popup editor for the
>    current paragraph on the current page that allows the user to edit the text and save the
>    changes*
>
> \* these edits are saved instantly over the same file

The one departure: it is written in Kotlin rather than ReactJS, as agreed ("it doesn't have to
be in ReactJS if there is another coding language that would work better, but it mustn't be a
wrapper app"). The PDF engine is a separate module with no UI code, so it could be wrapped as a
React Native native module later.
