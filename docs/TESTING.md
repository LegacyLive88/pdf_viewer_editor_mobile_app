# Testing

There are four layers. Each catches things the others cannot.

| Layer | What it proves | Command | Needs |
|---|---|---|---|
| Engine tests | The PDF engine reads, selects, highlights and edits correctly, on a corpus from many PDF producers | `./gradlew :pdfcore:testDebugUnitTest` | JDK 21 |
| App tests | The app's behaviour (the six features, saving back) with the real engine and real files | `./gradlew :app:testDebugUnitTest` | JDK 21 |
| Cross-check | Files the engine wrote are valid and say the same thing to *other* PDF software | `python3 tools/cross_validate.py` | Python packages below |
| Device tests | The real app on real Android: platform renderer, touch input, the file on disk | `./gradlew :app:connectedDebugAndroidTest` | emulator or phone |

Everything except the device tests runs on any machine with a JDK, in about a minute:
85 engine tests and 61 app tests.

```bash
./gradlew :pdfcore:testDebugUnitTest :app:testDebugUnitTest
```

```bash
python3 tools/cross_validate.py
```

## 1. Engine tests (`pdfcore/src/test`)

JVM tests using Robolectric, because PdfBox-Android needs Android classes.

| Class | Tests | Covers |
|---|---|---|
| `ExtractionTest` | 16 | opening; lines and paragraphs for every producer; rotation and page boxes; invisible, clipped and scanned text; encrypted files; pages with form fields, stamps or notes |
| `SelectionTest` | 11 | two-point selection, word snapping, reading order, columns, hyphenation, search |
| `HighlightTest` | 11 | writing, trimming and deleting highlights; foreign highlights; rollback |
| `ParagraphEditTest` | 25 | replacing paragraphs: operators, forms, transforms, rotation, alignment, fitting, fonts, repeated edits, file structures, rollback |
| `TextLayoutTest` | 6 | word wrapping and text clean-up (plain JUnit) |
| `GeometryTest` | 11 | page transforms, text frames, highlight trimming geometry (plain JUnit) |
| `FontBookTest` | 4 | which bundled font replaces which document font; character coverage |
| `LayoutDumpTest` | 1 | not an assertion: writes `build/test-artifacts/dumps/*.txt`, a readable dump of how each fixture is segmented |

### The fixture corpus

`pdfcore/src/test/resources/fixtures/` holds small PDFs from independent producers, because
each has its own habits:

| Fixture | Producer | Why it is there |
|---|---|---|
| `basic`, `justified`, `columns`, `form_shared`, `big` | reportlab | plain text; justification by word spacing; two columns; nested and shared form XObjects, clipped text; 300 pages |
| `operators`, `rotated`, `scan`, `tight` | hand-written | every text operator, leftover transforms, split streams; all rotations, crop/media box offsets; scans with and without OCR layer; tables and centred blocks |
| `lo_letter` | LibreOffice | embedded subset TrueType, kerned `TJ` arrays, lists, tables |
| `chrome_article` | Chromium (Skia) | flipped, scaled top-level transform; composite fonts |
| `cairo` | Cairo | cross-reference streams, object streams |
| `fpdf_unicode` | fpdf2 | Identity-H TrueType with accents, Cyrillic, Greek |
| `matplotlib_type3` | matplotlib | Type 3 fonts |
| `xref_stream`, `linearized`, `incremental`, `encrypted_owner`, `encrypted_user`, `annotated`, `form_stamp` | qpdf / by hand | file-structure variants; encryption; highlights "from another app"; a filled-in form field, a stamp and a note |

Regenerate with `python3 tools/make_fixtures.py` (needs reportlab, pikepdf, fpdf2, matplotlib,
LibreOffice, Chromium via Playwright, and poppler-utils; missing producers are skipped). The
fixtures are committed, so tests do not need any of that.

## 2. App tests (`app/src/test`)

| Class | Tests | Covers |
|---|---|---|
| `DocumentViewModelTest` | 27 | opening, the select gesture, copy/highlight/remove, paragraph editing, save copy, share, read-only sources, error messages, the notice for content that cannot be shown |
| `DocumentSourceTest` | 7 | writing only the tail vs the whole file, read-only detection, recent documents |
| `PdfPagesViewTest` | 16 | page layout, scrolling, zoom limits and focus, render requests, tap and double-tap reporting |
| `RenderComparisonTest` | 6 | the before/after picture comparison used to verify paragraph edits (plain JUnit) |
| `OpenSourceNoticesTest` | 5 | the licences screen names everything the app contains; a new library cannot be added without its licence (plain JUnit) |

The view model tests use the **real engine and real files**; only the pixel renderer is
replaced by `FakeRasterizer`, since Android's `PdfRenderer` does not exist on the JVM. After
each edit the tests open the *user's* file with a fresh engine instance and check its content –
they do not trust the app's own state.

## 3. Cross-check (`tools/cross_validate.py`)

The engine tests copy every PDF they write to `pdfcore/build/test-artifacts/` with a small
JSON file stating what must be true of it. The script then checks each file with four
implementations that share no code with the engine:

- **qpdf** opens it *without recovery* and reports no structural problems, and can tokenise
  every page's content;
- the **original file is a byte-for-byte prefix** (the save really was incremental);
- **PDFium, MuPDF and Poppler** agree on the page count, find the new text and no longer find
  the old;
- **PDFium** reports each highlight at the expected place – computed with PDFium's own
  page-to-device mapping, which independently verifies rotation and crop-box handling – and
  when the page is rendered the box is tinted while the text in it is still dark;
- rendered by **PDFium**, an edited page is pixel-identical to the original everywhere outside
  the edited paragraph, and pages that were not edited are identical altogether.

```bash
pip install pypdfium2 pymupdf pikepdf numpy pillow
```

```bash
python3 tools/cross_validate.py --render /tmp/renders
```

`--render` additionally writes a PNG of each page for looking at.

This layer has found real defects that the engine's own tests could not see: a trailer `/Size`
that shrank (PDFium then ignores objects), a cross-reference stream without its own entry, and
"selectable" text that no viewer displays because its form's bounding box clips it.

## 4. Device tests (`app/src/androidTest`)

`ViewerDeviceTest` (17 tests) launches the real activity through an "Open with" intent, sends
real touch events and checks pixels on screen, the clipboard and the file on disk. Screenshots
of the key moments are written to the app's `files/device-test-screens/`.

```bash
./gradlew :app:connectedDebugAndroidTest
```

```bash
adb exec-out run-as io.github.legacylive88.simplepdfs.debug tar c files/device-test-screens | tar x
```

Notes:
- The tests enable UI drawing themselves, so they also work on slimmed-down "ATD" emulator
  images, which switch drawing off.
- Touches are sent with exact timestamps so that double taps are recognised even on a slow
  emulator.
- The system file picker, "create document" screen and share sheet are *not* driven: they
  belong to the system and differ per device. What the app hands to them is tested instead.

## What is not covered by any automated test

- Whether a re-typeset paragraph *looks good* – checked by eye from renders. (That nothing
  *else* on the page changed is checked automatically, by pixel comparison.)
- Two-finger pinch on real hardware; the system picker and share sheet; cloud storage
  providers; Android versions other than the one the device tests ran on.
- Power loss during a save.
- Very large files (hundreds of MB) and memory pressure.
- Real-world PDFs beyond the fixture corpus. The corpus is broad but synthetic; paragraph
  detection in particular is heuristic and will meet layouts it gets wrong.

## When a feature changes

Update, in this order: the test(s) named for it in [FEATURES.md](FEATURES.md), the entry in
FEATURES.md itself (decisions, assumptions, "tested today"), and this file's tables if tests
were added or removed.
