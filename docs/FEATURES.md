# Features

For each feature: what it does, the decisions and assumptions behind it, what ought to be
tested, and what is tested today. Test names are given so they can be found with a search.
How to run the tests is in [TESTING.md](TESTING.md).

Test locations:

| Short name | Path | Runs on |
|---|---|---|
| **engine** | `pdfcore/src/test/…/pdfcore/` | JVM (Robolectric) |
| **app** | `app/src/test/…/simplepdfs/` | JVM (Robolectric) |
| **device** | `app/src/androidTest/…/ViewerDeviceTest.kt` | emulator or phone |
| **cross-check** | `tools/cross_validate.py` | qpdf, PDFium, MuPDF, Poppler |

---

## Opening and viewing

Not one of the six numbered features, but everything else stands on it.

**What it does.** A PDF is opened from the start screen (system file picker), from the list of
recent documents, through "Open with" in another app, or by sharing a PDF to the app. Pages are
shown as one continuous vertical strip; the title bar shows the file name and "Page x of n".

**Decisions**
- Pages are drawn by Android's `PdfRenderer`. No renderer is bundled.
- The app works on a private copy of the document (see ARCHITECTURE.md → life cycle).
- The app requests **no permissions**. It only ever sees files the user picks or another app
  hands over, and it never goes online.
- One document at a time (`singleTask`). Opening another replaces the current one; nothing is
  lost because edits are saved as they are made.
- Page sizes are measured in the background; until then every page is assumed to be the size of
  the first. For the usual uniform document nothing moves.
- Each visible page has a bitmap at screen width; when zoomed in, the visible part is rendered
  again at the exact zoom. Memory use therefore does not grow with zoom or page count.
- **The app says when it cannot show something.** Android's renderer leaves out things that
  were added on top of a page: stamps, sticky notes and drawings on every Android version,
  and before Android 15 also whatever was typed into form fields. A filled-in form that looks
  empty, or a page missing its "Approved" stamp, would mislead its reader. So when a PDF
  contains such things, a message names them as it opens ("This PDF contains notes and
  stamps, which this app cannot display. Parts of a page may look empty."). Highlights,
  underlines and strike-outs are drawn by the app itself and are always shown.
- **Nothing is backed up or moved to a new phone** (`allowBackup="false"` plus
  `res/xml/data_extraction_rules.xml` for Android 12+). The app's storage holds working copies
  of the user's documents, which are private, and links to recent documents, which only work
  on the device they were made on.

**Assumptions**
- The device has enough free cache space for a copy of the document (two after the first edit).
- What the renderer draws is **measured on the device**, not looked up by Android version –
  the renderer is a system component that is updated on its own. Once per run the app renders
  a tiny PDF it carries in code (`AnnotationProbe`: six black squares on a white page, each
  square a different type of annotation) and sees which squares come out. On the emulators
  used for testing, Android 7 and 14 draw none and Android 16 draws form fields only. A device
  test compares the measurement with what is really on the screen for a real document.
- The app only detects that such annotations exist (`PdfSession.otherAnnotationKinds`); it
  does not draw them. Drawing them itself is possible future work.
- Password-protected PDFs are not supported yet and are reported as such.
- **Screen readers:** the bars, buttons, dialogs and messages are labelled, but the pages
  themselves are pictures to TalkBack – the text on them is not read out. Selecting by tapping
  words needs sight.

**Should be tested**
- Opening from each entry point; a file that is not a PDF; an empty file; a password-protected
  file; a very large file; pages of mixed size and rotation; turning the phone; process death
  and return.
- Rendering is correct and sharp at every zoom level; memory stays bounded on long documents.

**Tested today**
- engine `ExtractionTest`: `basicDocumentIsSegmentedIntoItsParagraphs`,
  `pageRotationAndBoxesGiveTheDisplayedSize`, `structuralVariantsOpen`,
  `somethingThatIsNotAPdfIsRejectedCleanly`, `passwordProtectedDocumentAsksForPassword`,
  `aLargeDocumentOpensAndAnyPageCanBeRead`
- app `DocumentViewModelTest`: `openingADocumentShowsItAndThenMakesTextToolsAvailable`,
  `pagesOfDifferentSizesAreMeasuredInTheBackground`, `aFileThatIsNotAPdfIsReportedAndForgotten`,
  `aPasswordProtectedFileIsExplained`,
  `openingAnotherDocumentReplacesTheFirstAndClosingReturnsToTheStartScreen`
- app `PdfPagesViewTest`: `pagesAreStackedAtFullWidthAndTheFirstOneIsVisible`,
  `visiblePagesAndTheNextOneAreRenderedAtTheWidthOfTheView`,
  `aRequestThatIsCancelledAndReplacedIsNotMistakenForItsReplacement`, `scrollingStopsAtTheEndsAndReportsThePage`,
  `newPageSizesKeepTheReaderOnTheSamePage`, `withoutADocumentNothingHappens`
- engine `ExtractionTest.formFieldsStampsAndNotesAreReportedByKind`
- app `DocumentViewModelTest`: `aDocumentWithFormFieldsOrStampsNamesWhatCannotBeShown`,
  `onlyWhatTheRendererLeavesOutIsMentioned`
- app `AnnotationProbeTest` (3 tests): the probe document is strictly well-formed and holds
  one annotation of each type; squares are read as the right kinds
- cross-check: the probe document in qpdf, PDFium (four ways: without annotations, with them,
  with the form module only, with both) and MuPDF
- device: `opensADocumentAndDrawsItsPages` (checks real pixels: white paper, ink on the title),
  `aFileThatIsNotAPdfShowsAnErrorInsteadOfCrashing`,
  `aLargeDocumentOpensAndCanBeEditedOnALaterPage`,
  `aProtectedDocumentCanBeViewedAndSelectedButNotChanged`,
  `aDocumentWithFormFieldsOrStampsNamesWhatCannotBeShown` (also reads off the screen whether
  this Android version really draws the form field and the stamp),
  `turningThePhoneKeepsTheDocumentAndThePlaceInIt`
- **Not automated:** the system file picker and "Open with" from other apps (they belong to
  the system); return after process death; behaviour under memory pressure.

---

## 1. Save copy

**What it does.** Writes the document, including every edit made so far, to a place the user
chooses in the system's "create document" screen. The suggested name is `<name> (copy).pdf`.
The app keeps working on the original.

**Decisions**
- A copy is a byte-for-byte snapshot of the engine's current file, taken on the engine thread
  so it cannot change while it is being written.
- "Save as…" (in the read-only banner, see below) is the same operation, except that the new
  file then becomes the document being edited.

**Assumptions**
- The chosen location accepts a truncating write (`wt`). Every storage provider that offers
  "create document" does.

**Should be tested**
- The copy equals the document as shown, including edits; the original is unaffected and stays
  the document being edited; saving before the text engine has loaded; a location that fails.

**Tested today**
- app: `saveCopyWritesTheDocumentWithItsEditsAndStaysOnTheOriginal`,
  `saveCopyBeforeTheTextEngineIsReadyCopiesTheOriginal`
- engine `HighlightTest.copyToWritesTheCurrentDocument`
- device: `feature1_saveCopyWritesTheEditedDocumentAndKeepsWorkingOnTheOriginal`
- **Not automated:** the system's "create document" screen itself; a destination that fails
  part-way (the error message path is exercised only by code review).

---

## 2. Share

**What it does.** Opens Android's share sheet with the document (including edits) attached.

**Decisions**
- What is shared is a snapshot copied to `cache/shared/<file name>`, handed out through a
  `FileProvider` with read permission for the receiving app only. The folder is emptied before
  each share, so at most one shared copy exists.
- The shared file keeps the document's own name (characters illegal in file names replaced).

**Assumptions**
- The receiving app reads the file reasonably promptly; the copy is replaced at the next share.

**Should be tested**
- The shared file equals the current document, has the right name and MIME type, and is
  readable through the provider URI; sharing after edits; sharing before the engine is ready.

**Tested today**
- app: `shareProducesACopyNamedLikeTheDocument`
- device: `feature2_shareHandsOutAReadableCopyOfTheDocument` (reads the bytes back through the
  real `FileProvider` and checks the MIME type)
- **Not automated:** the share sheet and the receiving app.

---

## 3. Show / hide tools

**What it does.** "Hide" removes the title bar and the tool bar so the pages get the whole
screen. A small round button in the top corner brings them back; so does the Back button.

**Decisions**
- The bars lie on top of the pages rather than beside them. Hiding them therefore does not
  resize or re-render anything, and the page under the reader's eye does not jump.
- One control stays on screen while tools are hidden – otherwise there would be no way back.
- Tools re-appear automatically while a selection is in progress or exists, because the
  selection's actions live in the tool bar.
- The system status bar stays; only the app's own bars hide.

**Should be tested**
- Hide and show; the page position is kept; first and last page can still be scrolled clear of
  the bars in both states; Back restores the tools.

**Tested today**
- app: `toolsCanBeHiddenAndShown`,
  `PdfPagesViewTest.toolbarsThatCoverThePagesMoveTheContentClearOfThem`
- device: `feature3_toolsCanBeHiddenAndShownAgain`
- **Not automated:** Back-button handling; appearance with gesture vs. three-button navigation.

---

## 4. Zoom in / out

**What it does.** Two buttons step the magnification (×1.4 per tap) around the middle of the
screen, and two fingers pinch freely. Range 0.5× to 8×, where 1× means "page fills the width".

**Decisions**
- **Double tap does not zoom**, because the README assigns double tap to editing a paragraph.
  Double-tap-and-drag zooming is switched off for the same reason.
- While a gesture is in progress the existing bitmap is scaled; a sharp patch for the visible
  area follows 140 ms after things settle.
- Buttons grey out at the limits.

**Should be tested**
- Steps and limits; the point under the centre (or between the fingers) stays put; text is
  sharp after settling; taps still land on the right text at any zoom; horizontal scrolling
  when zoomed in; centred when zoomed out.

**Tested today**
- app `PdfPagesViewTest`: `zoomButtonsStepWithinLimitsAndKeepTheCentreInPlace`,
  `whenZoomedInTheVisiblePartIsRenderedSharply`, `screenPositionsMapBackToPagePositions`
- device: `feature4_zoomButtonsChangeTheMagnification`
- **Not automated:** pinch with two real fingers (the code path is the same `zoomTo` the
  buttons use; the gesture recognition itself is Android's).

---

## 5. Select → copy, highlight, remove highlight

**What it does.** Tap **Select**, tap the first word, tap the last word *on the same page*. The
text between is selected and the Select button switches itself off. The tool bar then offers
**Copy**, **Highlight**, **Remove highlight** and **Done**. Highlighting and removing are saved
into the file immediately.

**Decisions**
- **Whole words are selected.** A finger cannot reliably hit one letter, and the README
  describes selecting text between two points, not characters.
- **Taps snap to the nearest text**, counting distance along a row far less than across rows:
  a tap in the margin beside a line means that line.
- "Between" means **reading order** (content order, paragraph by paragraph), not a rectangle.
  In a two-column document a selection stays in its column.
- The first tapped word is outlined so it is clear the tap registered, and a line of
  instructions shows what to do next.
- A second tap on a *different* page is ignored with a message, as the README restricts the
  gesture to one page.
- While selecting, a double tap counts as two taps – it does not open the editor.
- **Copy** joins the lines of a paragraph with spaces, re-joins words hyphenated across a line
  break, and separates paragraphs with a line break. Copying keeps the selection.
- **Highlight** writes a standard PDF highlight annotation (yellow `#FFEB3B`, multiply blend)
  with an appearance stream, so other viewers show it. Highlighting text that is already
  highlighted replaces the overlap instead of stacking a second layer.
- **Remove highlight** removes highlighting *from the selected text only*: a longer highlight
  is trimmed or split, not deleted. It also works on highlights made in other apps. Leftovers
  thinner than a character (padding of other apps' highlights) are dropped.
- Other markup (underline, strike-out, squiggly) is displayed but never changed.
- If the document's author disallowed copying, the selection can be shown but Copy explains
  why it does nothing. Encrypted documents cannot be highlighted (see "Saving").

**Assumptions**
- The PDF contains real text. Scanned pages without a text layer have nothing to select, and
  the app says so. Scans *with* an OCR layer can be selected, copied and highlighted.
- Text is written in roughly reading order in the file, which is true of all common producers.
- Right-to-left text is selected geometrically; copied text may come out in visual order.

**Should be tested**
- The gesture: button → tap → tap → selection; button off afterwards; order of taps
  irrelevant; same word twice; taps in empty space; second tap on another page; abandoning.
- Text: across lines, paragraphs, columns; hyphenation; every producer; rotated pages.
- Highlight: appears in other viewers at the right place, including rotated/cropped pages;
  no stacking; survives reopening; original bytes untouched.
- Remove: whole, part, split in the middle; foreign highlights in every quad-point order;
  nothing to remove; other markup untouched.
- A tap is acted on exactly once (see the regression note below).

**Tested today**
- engine `SelectionTest` (11 tests), e.g. `selectsTheTextBetweenTwoPointsInReadingOrder`,
  `theOrderOfTheTwoTapsDoesNotMatter`, `wholeWordsAreSelectedEvenWhenTheTapHitsTheMiddleOfAWord`,
  `aTapInEmptySpaceSnapsToTheNearestText`, `selectionInsideAColumnStaysInThatColumn`,
  `selectionOnRotatedPagesHasBoxesOnTheText`, `wordsBrokenAcrossLinesAreRejoined`,
  `wordsAndPhrasesCanBeFound`
- engine `HighlightTest` (11 tests), e.g.
  `highlightIsWrittenAsAStandardAnnotationAndSurvivesReopening`,
  `highlightingTheSameTextTwiceDoesNotStackHighlights`,
  `removingAHighlightFromPartOfItTrimsIt`,
  `removingAllHighlightedTextDeletesTheAnnotationAndKeepsTheFileValid`,
  `highlightsFromOtherApplicationsAreShownAndCanBeRemoved`,
  `highlightsOnRotatedAndOffsetPagesLandOnTheText`, `highlightsWorkOnEveryFileStructure`
- engine `GeometryTest`: the trimming geometry, including sideways text
- app `DocumentViewModelTest`: `selectButtonThenTwoTapsSelectsTheTextBetweenThemAndSwitchesSelectOff`,
  `twoQuickTapsAreHandledInOrderEvenWhileTheEngineIsStillLoading`,
  `theSecondTapMustBeOnTheSamePage`, `aPageWithoutTextSaysSo`,
  `pressingSelectAgainAbandonsTheSelectionAndATapClearsAFinishedOne`,
  `copyHandsTheSelectedTextToTheScreen`, `highlightIsSavedInstantlyOverTheSameFile`,
  `removeHighlightTrimsAndWithoutAHighlightSaysSo`,
  `aProtectedDocumentCanBeViewedAndSelectedButNotChanged`
- app `PdfPagesViewTest`: `whileSelectingEveryTapCountsImmediatelyAndOnlyOnce`,
  `aDoubleTapWhileSelectingIsTwoTaps`,
  `tapsBetweenPagesGoToTheNearerPageAndTapsBesideAPageAreClamped`
- device: `feature5_selectThenHighlightAndRemoveHighlightAreSavedInTheFile` (real touches,
  clipboard content, tinted pixels on screen, the file on disk), and
  `rotatedPagesAndOtherProducersRenderWithOverlaysInPlace`
- cross-check: every highlighted file – annotation count, position as PDFium maps it, the box
  really tinted and the text under it still dark when rendered.

> **Regression note.** The first on-device run found that a selection vanished right after it
> was made: Android's gesture detector reports a tap immediately *and* confirms it ~300 ms
> later, and the confirmation was treated as a new tap. Guarded by
> `whileSelectingEveryTapCountsImmediatelyAndOnlyOnce`.

---

## 6. Double tap → edit paragraph

**What it does.** With Select off, double-tapping a paragraph opens a small editor holding that
paragraph's text. **Save** replaces the paragraph in the PDF, immediately and in the same file.

**Decisions**
- **The text is really replaced** – removed from the page content and typeset anew – not
  painted over. Copying or searching the edited file finds the new text only.
- **The paragraph is re-set in a bundled font**: Liberation Sans, Serif or Mono, whichever
  matches the original (by name, then by the font's own flags), in the matching bold/italic.
  The editor says which font and size will be used. This is unavoidable in general: PDFs embed
  only the glyphs they use, so the original font usually cannot draw new characters. The
  Liberation fonts have the same character widths as Arial/Helvetica, Times New Roman and
  Courier New, so paragraphs in those very common fonts keep their line breaks.
- **Kept from the original:** position, size, colour, line spacing, alignment (left, centred,
  right, justified), first-line indent or hanging indent, and text direction on rotated pages.
- **Mixed formatting inside a paragraph is not kept**: the new text uses the paragraph's
  dominant font and colour. A bold word in the middle becomes regular.
- **Fitting.** Text wraps at the paragraph's width (a single line may grow to the edge of the
  text column). If it needs more lines than there is room before the next text or the bottom
  margin, the font is scaled down in steps to 60 % at most; only then may it overlap what
  follows. Both cases are reported after saving.
- **Characters the fonts lack** (Chinese, emoji, …) are refused *before* anything is written,
  inside the editor, so the text can be corrected. Liberation covers Latin, Greek and
  Cyrillic; DejaVu Sans supplies many symbols.
- Line breaks typed in the editor are kept; tabs become four spaces.
- Saving unchanged text writes nothing.
- An empty text deletes the paragraph.
- Words hyphenated across lines are shown re-joined in the editor.
- Not editable, each with its own message: text of a scan's OCR layer (invisible text);
  paragraphs containing glyphs the PDF does not map to characters; right-to-left and vertical
  text; anything in an encrypted document.
- Text that cannot be seen (outside the page, or clipped by its form's bounding box) is not
  offered for editing or selection.

**Assumptions**
- What counts as "a paragraph" is inferred from geometry (ARCHITECTURE.md → Reading text).
  It is right for ordinary documents and can be wrong for unusual layouts: a table row may be
  taken as one paragraph, or two separate short lines as one. The editor shows exactly what
  will be replaced, so a wrong guess is visible before saving.
- Decorations drawn separately from the text (underlines, link boxes, coloured backgrounds)
  stay where they were.
- In tagged (accessible) PDFs the new text is not added to the structure tree.

**Should be tested**
- Replacement leaves every other glyph exactly in place – for each text operator, for partial
  operators, in nested and shared forms, with leftover graphics state, on rotated and cropped
  pages, for every font type and producer.
- Typesetting: wrapping, alignment, indent, justification, fitting, overflow, manual breaks.
- Characters: accents, symbols, other alphabets, unsupported ones.
- Repeated edits in one session and across sessions; file size does not balloon.
- The edited paragraph stays in its place in reading order.
- Failure leaves the document exactly as it was.
- The result opens and reads correctly in other PDF software.

**Tested today**
- engine `ParagraphEditTest` (25 tests), e.g.
  `replacesAParagraphAndLeavesEverythingElseExactlyWhereItWas`,
  `everyKindOfTextOperatorCanBeEdited`, `partOfATextOperatorCanBeRemovedWithoutMovingTheRest`,
  `textInsideASharedFormIsEditedOnThisPageOnly`,
  `pagesWithALeftoverCoordinateTransformGetUprightTextInTheRightPlace`,
  `paragraphsOnRotatedPagesKeepTheirPlaceAndDirection`, `justifiedParagraphsAreJustifiedAgain`,
  `aCentredTitleStaysCentredAndARightAlignedLineStaysRightAligned`,
  `longerTextWrapsWithinTheParagraphWidthAndShrinksOnlyWhenItRunsOutOfRoom`,
  `textThatCannotFitIsReportedAsOverflowing`, `aParagraphCanBeDeletedByEmptyingIt`,
  `charactersTheBuiltInFontsLackAreRefusedBeforeAnythingIsChanged`,
  `accentsSymbolsAndOtherAlphabetsAreWritten`,
  `aParagraphCanBeEditedAgainInTheSameSessionAndInALaterOne`,
  `documentsFromOtherProducersCanBeEdited`, `embeddedUnicodeAndType3FontsCanBeEdited`,
  `everyFileStructureCanBeEditedAndSaved`, `aFailedVerificationLeavesTheDocumentAsItWas`,
  `editingAPageDeepInsideALargeDocumentIsQuick`
- engine `TextLayoutTest` (6), `FontBookTest` (4), `ExtractionTest` (paragraph detection per
  producer, clipped and invisible text)
- app: `doubleTapOnAParagraphOffersItForEditingAndSavingRewritesTheFile`,
  `doubleTapWhileSelectingCountsAsATap`,
  `doubleTapAwayFromTextOrOnAScanExplainsWhyNothingOpens`,
  `charactersTheFontsLackAreReportedToTheEditorAndNothingIsWritten`,
  `aLongEditReportsThatTheTextWasShrunkOrOverflows`,
  `PdfPagesViewTest.aDoubleTapIsReportedOnceAndNotAsTaps`,
  `PdfPagesViewTest.anEditedPageIsRenderedAgainWhileTheOldPictureStaysUp`
- device: `feature6_doubleTapOpensTheParagraphEditorAndSavingRewritesTheFile`,
  `feature6_charactersTheFontsLackAreExplainedInTheEditor`,
  `feature6_doubleTapWhereThereIsNoTextDoesNothingHarmful`,
  `aLargeDocumentOpensAndCanBeEditedOnALaterPage`
- cross-check: every edited file – structure (qpdf), new text present and old text gone
  according to PDFium, MuPDF and Poppler, original bytes an untouched prefix, and **rendered
  by PDFium the page is pixel-identical to the original outside the edited paragraph** (other
  pages identical altogether).
- **Not automated:** whether the *new* paragraph looks good (font match, spacing). Renders were
  inspected by eye while building (`tools/cross_validate.py --render`).

---

## Saving ("these edits are saved instantly over the same file")

Applies to features 5 and 6.

**Decisions**
- Each edit is written as a PDF incremental update and then mirrored to the document's real
  location; where the provider allows, only the bytes after the untouched original are
  written. Details in ARCHITECTURE.md → Saving.
- **Nothing is kept unless it verifies.** After writing, the file must open in Android's own
  PDF implementation with the same page count, and the changed pages must render. After a
  paragraph edit the page is additionally drawn before and after, and **outside the edited
  paragraph the two pictures must be identical**. Otherwise the edit is rolled back and the
  user is told the document was not changed. This costs two small page renders per edit and
  means an edit can be refused on a page where it would have been harmless – the safe side to
  err on with someone's document.
- "Outside the edited paragraph" allows a margin around the old and the new text, because
  letters overhang their boxes (italics, accents, long descenders): 6 points for ordinary
  text, growing with the size of the type (35 % of it) up to 24 points for headlines.
- **Read-only locations** (mail attachments, some downloads): the edit is applied to the app's
  copy, a banner explains that it is not in the original, and "Save as…" stores it in a new
  file that then becomes the document.
- **Encrypted documents are view-only**, even when they open without a password. Writing into
  an encrypted file is easy to get subtly wrong, and the result of getting it wrong is a file
  nothing can open.
- There is no undo. Because saving is instant, an unwanted edit has to be edited back.

**Assumptions**
- Nobody else changes the file while it is open. If its length is not what the app left, the
  whole file is rewritten with the app's version (last writer wins).

**Should be tested**
- Tail-only writing when possible, full rewrite otherwise, clean failure when impossible.
- Verification failure → rollback → session still usable.
- Every cross-reference flavour (classic table, streams, hybrid/linearised, earlier updates).
- Power loss in the middle of a write.

**Tested today**
- app `DocumentSourceTest` (7 tests): `onlyTheChangedTailIsWrittenWhenTheRemoteCopyIsAsWeLeftIt`,
  `theWholeFileIsWrittenWhenTheRemoteCopyChangedBehindOurBack`,
  `aShorterDocumentLeavesNoLeftoverBytes`, `providersWithoutRandomAccessGetAFullRewrite`,
  `aReadOnlyLocationIsDetectedAndWritingToItFailsCleanly`
- app: `aReadOnlyDocumentKeepsEditsInTheAppUntilTheyAreSavedSomewhereElse`,
  `aProviderWithoutRandomAccessGetsTheWholeFileRewritten`
- engine: `aFailedVerificationRollsTheEditBack`, `aFailedVerificationLeavesTheDocumentAsItWas`,
  `everyFileStructureCanBeEditedAndSaved`, `highlightsWorkOnEveryFileStructure`
- app `RenderComparisonTest` (6 tests): the picture comparison itself, including the margin
  for large type
- device: `theSaveVerifierAcceptsACleanEditAndRejectsOneThatChangesSomethingElse` – the real
  verifier with the real renderer, including a change outside the declared area being refused
- device: both feature tests check that the file on disk grew and that its original bytes are
  an untouched prefix.
- cross-check: qpdf opens every written file *without recovery* and reports no problems.
- **Not tested:** power loss during a write (the design limits the damage to the appended
  tail, but this has not been exercised); real cloud storage providers.

---

## About and open-source licences

Not one of the six features; it is there because the app contains other people's work.

**What it does.** The menu's **About** shows the app's name and version and what it is built
with. **Open-source licences** opens the full licence texts of every library and font inside
the app.

**Decisions**
- The licences are shown **in the app**, not only shipped as files inside it. The Apache
  licence (PDF library, AndroidX, Kotlin) asks that recipients are given a copy, and the Open
  Font License asks that the font licence can be "easily viewed by the user".
- The texts are kept **verbatim** in the repository: `app/src/main/assets/licenses/
  THIRD_PARTY_NOTICES.txt` (assembled from the `LICENSE` and `NOTICE` files inside the Apache
  PDFBox and FontBox 2.0.27 jars and from Bouncy Castle's own licence class) and
  `pdfcore/src/main/assets/fonts/LICENSE.txt` beside the fonts.
- For display the hard line breaks inside paragraphs are removed (`OpenSourceNotices.reflow`),
  because text wrapped at 78 columns breaks a second time on a phone. No word is changed.
- A test fails when a library is added to the build without being added to the notices.

**Assumptions**
- The list is maintained by hand when dependencies change (the test above is the reminder).
- Libraries pulled in indirectly by AndroidX and Kotlin are under the same Apache licence as
  their parents.

**Should be tested**
- Every component in the app is named with its licence; reflowing changes no word; the screen
  opens from the menu and shows the text; a new dependency cannot be added unnoticed.

**Tested today**
- app `OpenSourceNoticesTest` (5 tests): `wrappedLinesAreJoinedAndShortLinesKeepTheirBreak`,
  `copyrightStatementsAndDifferentlyIndentedLinesAreNeverRunTogether`,
  `reflowingTheRealLicencesChangesNoWord`, `everyComponentInTheAppIsNamedWithItsLicence`,
  `everyLibraryTheBuildShipsIsCoveredByTheNotices`
- device: `aboutShowsTheVersionAndTheOpenSourceLicences`
- **Not automated:** whether the list of *indirect* dependencies is complete.
