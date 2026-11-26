#!/usr/bin/env python3
"""Checks every PDF written by the pdfcore tests with independent PDF implementations.

The unit tests prove that the engine can read back what it wrote. That is necessary but not
sufficient: the engine could be consistently wrong. This script re-examines the same files with
four implementations that share no code with it:

    PDFium   (pypdfium2)  - the engine behind Chrome and Android's PdfRenderer
    MuPDF    (pymupdf)
    qpdf     (pikepdf)    - strict structural checker
    Poppler  (pdftotext)

For each artifact `<name>.pdf` there is a `<name>.json` written by the test (see
PdfTestBase.publish) that states what must be true:

    pages              page count
    must_contain       text that has to be extractable per page
    must_not_contain   text that has to be gone per page
    highlights         expected highlight boxes, "page:left,top,right,bottom" in display space
    highlight_count    number of highlight annotations per page
    original           file whose bytes the artifact must start with (incremental save)
    changed_areas      boxes "page:left,top,right,bottom" where an edit removed or put text
    unchanged_pages    pages that must look exactly like the original

Checks per artifact:
  * qpdf opens it without recovery and reports no structural problems
  * the bytes of the original are an untouched prefix
  * PDFium, MuPDF and Poppler agree on page count and the text expectations
  * PDFium reports the expected highlight annotations at the expected place; the place is
    computed with PDFium's own page-to-device mapping, so page rotation and crop boxes are
    verified independently of the engine's geometry code
  * rendered with PDFium, each highlight box is actually tinted and the text in it is still dark
  * rendered with PDFium, an edited page is pixel-identical to the original everywhere outside
    the edited area, and pages listed as unchanged are identical altogether

Usage:
    ./gradlew :pdfcore:testDebugUnitTest
    python3 tools/cross_validate.py [artifact-dir] [--render out-dir]

Requirements (dev machine only): pip install pypdfium2 pymupdf pikepdf numpy ; poppler-utils
"""
import ctypes
import json
import os
import re
import shutil
import subprocess
import sys
import warnings

warnings.filterwarnings("ignore")

args = [a for a in sys.argv[1:]]
render_dir = None
if "--render" in args:
    i = args.index("--render")
    render_dir = os.path.abspath(args[i + 1])
    del args[i:i + 2]
    os.makedirs(render_dir, exist_ok=True)
ROOT = os.path.abspath(args[0] if args else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "pdfcore", "build", "test-artifacts"))

import numpy as np  # noqa: E402
import pikepdf  # noqa: E402
import pypdfium2 as pdfium  # noqa: E402
import pypdfium2.raw as raw  # noqa: E402

try:
    import pymupdf  # noqa: E402
except ImportError:  # older installs
    import fitz as pymupdf  # noqa: E402

PDFTOTEXT = shutil.which("pdftotext")
SCALE = 2.0
failures = []
checked = 0


def norm(s):
    """Whitespace-insensitive comparison: extractors differ in where they put spaces and breaks."""
    s = s.replace("\u00AD", "-").replace("\uFB01", "fi").replace("\uFB02", "fl").replace("\u00A0", " ")
    return re.sub(r"\s+", "", s)


def fail(artifact, msg):
    failures.append("%s: %s" % (artifact, msg))


def pdfium_text(doc, i):
    page = doc[i]
    tp = page.get_textpage()
    try:
        return tp.get_text_range()
    finally:
        tp.close()
        page.close()


def to_display(page, w, h, x, y):
    dx = ctypes.c_int()
    dy = ctypes.c_int()
    # Map through a large virtual device for sub-point precision.
    k = 100
    raw.FPDF_PageToDevice(page.raw, 0, 0, int(round(w * k)), int(round(h * k)), 0, x, y, ctypes.byref(dx), ctypes.byref(dy))
    return dx.value / k, dy.value / k


def pdfium_highlights(doc, i):
    """Returns [(left, top, right, bottom)] in display space for each quad of each highlight."""
    page = doc[i]
    w, h = page.get_size()
    boxes = []
    count = 0
    n = raw.FPDFPage_GetAnnotCount(page.raw)
    for a in range(n):
        annot = raw.FPDFPage_GetAnnot(page.raw, a)
        try:
            if raw.FPDFAnnot_GetSubtype(annot) != raw.FPDF_ANNOT_HIGHLIGHT:
                continue
            flags = raw.FPDFAnnot_GetFlags(annot)
            if flags & 2:
                continue
            count += 1
            for q in range(raw.FPDFAnnot_CountAttachmentPoints(annot)):
                qp = raw.FS_QUADPOINTSF()
                raw.FPDFAnnot_GetAttachmentPoints(annot, q, ctypes.byref(qp))
                pts = [to_display(page, w, h, qp.x1, qp.y1), to_display(page, w, h, qp.x2, qp.y2),
                       to_display(page, w, h, qp.x3, qp.y3), to_display(page, w, h, qp.x4, qp.y4)]
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                boxes.append((min(xs), min(ys), max(xs), max(ys)))
        finally:
            raw.FPDFPage_CloseAnnot(annot)
    page.close()
    return count, boxes


def render(doc, i, annots):
    page = doc[i]
    bitmap = page.render(scale=SCALE, may_draw_forms=False, draw_annots=annots)
    arr = bitmap.to_numpy().copy()
    page.close()
    # BGR(A/X) -> RGB
    if arr.shape[2] >= 3:
        arr = arr[:, :, [2, 1, 0]]
    return arr


def check(json_path):
    global checked
    spec = json.load(open(json_path, encoding="utf-8"))
    folder = os.path.dirname(json_path)
    path = os.path.join(folder, spec["file"])
    name = os.path.relpath(path, ROOT)
    checked += 1

    # --- structure (qpdf) -------------------------------------------------------------------
    try:
        with pikepdf.open(path, attempt_recovery=False) as pdf:
            problems = pdf.check_pdf_syntax() if hasattr(pdf, "check_pdf_syntax") else pdf.check()
            if problems:
                fail(name, "qpdf reports: " + "; ".join(problems[:3]))
            if len(pdf.pages) != spec["pages"]:
                fail(name, "qpdf sees %d pages, expected %d" % (len(pdf.pages), spec["pages"]))
            # every page's content must decode and tokenise
            for n, page in enumerate(pdf.pages):
                try:
                    pikepdf.parse_content_stream(page)
                except Exception as e:  # noqa: BLE001
                    fail(name, "qpdf cannot parse content of page %d: %s" % (n, e))
    except Exception as e:  # noqa: BLE001
        fail(name, "qpdf cannot open the file without recovery: %s" % e)

    # --- incremental save: original bytes untouched -----------------------------------------
    if "original" in spec:
        original = open(os.path.join(folder, spec["original"]), "rb").read()
        data = open(path, "rb").read()
        if not data.startswith(original):
            fail(name, "the original file is not an untouched prefix of the saved file")
        if len(data) <= len(original):
            fail(name, "nothing was appended")

    # --- PDFium -----------------------------------------------------------------------------
    doc = pdfium.PdfDocument(path)
    if len(doc) != spec["pages"]:
        fail(name, "PDFium sees %d pages, expected %d" % (len(doc), spec["pages"]))
    mu = pymupdf.open(path)
    if mu.page_count != spec["pages"]:
        fail(name, "MuPDF sees %d pages, expected %d" % (mu.page_count, spec["pages"]))

    pages_with_text = set(spec["must_contain"]) | set(spec["must_not_contain"])
    for key in sorted(pages_with_text, key=int):
        i = int(key)
        texts = {"PDFium": pdfium_text(doc, i), "MuPDF": mu[i].get_text()}
        if PDFTOTEXT:
            r = subprocess.run([PDFTOTEXT, "-f", str(i + 1), "-l", str(i + 1), "-enc", "UTF-8", path, "-"],
                               capture_output=True)
            texts["Poppler"] = r.stdout.decode("utf-8", "replace")
        for engine, text in texts.items():
            n = norm(text)
            words = set(re.findall(r"\S+", text.replace("\u00a0", " ")))
            for needle in spec["must_contain"].get(key, []):
                if norm(needle) in n:
                    continue
                # Extractors order the lines of sideways or multi-column text differently (PDFium
                # lists the lines of a 90 degree page bottom-up, even in untouched files). Text
                # that wrapped may therefore not be contiguous; then every word must be there.
                missing = [w for w in needle.split() if w not in words]
                if missing:
                    fail(name, "%s does not find %r on page %d (missing %s)" % (engine, needle, i, missing[:4]))
            for needle in spec["must_not_contain"].get(key, []):
                if norm(needle) in n:
                    fail(name, "%s still finds %r on page %d" % (engine, needle, i))

    # --- highlights -------------------------------------------------------------------------
    for key, expected in spec.get("highlight_count", {}).items():
        i = int(key)
        count, _ = pdfium_highlights(doc, i)
        if count != expected:
            fail(name, "PDFium sees %d highlights on page %d, expected %d" % (count, i, expected))
        mu_count = sum(1 for a in (mu[i].annots() or []) if a.type[1] == "Highlight" and not (a.flags & 2))
        if mu_count != expected:
            fail(name, "MuPDF sees %d highlights on page %d, expected %d" % (mu_count, i, expected))

    by_page = {}
    for item in spec.get("highlights", []):
        page_s, box_s = item.split(":")
        by_page.setdefault(int(page_s), []).append(tuple(float(v) for v in box_s.split(",")))
    for i, boxes in by_page.items():
        _, actual = pdfium_highlights(doc, i)
        with_annots = render(doc, i, True)
        without = render(doc, i, False)
        for box in boxes:
            # position, as PDFium maps it
            if not any(all(abs(a[k] - box[k]) <= 0.6 for k in range(4)) for a in actual):
                fail(name, "page %d: no PDFium highlight box at %s (found %s)" % (
                    i, box, [tuple(round(v, 1) for v in a) for a in actual]))
                continue
            # pixels: tinted with annotations, and the text underneath still dark
            l, t, r, b = [int(round(v * SCALE)) for v in box]
            l, t = max(l + 1, 0), max(t + 1, 0)
            r, b = min(r - 1, with_annots.shape[1]), min(b - 1, with_annots.shape[0])
            if r <= l or b <= t:
                fail(name, "page %d: highlight box %s is empty on the rendered page" % (i, box))
                continue
            region = with_annots[t:b, l:r, :3].astype(np.int32)
            plain = without[t:b, l:r, :3].astype(np.int32)
            # background pixels (white without annotations) must have changed colour
            background = plain.min(axis=2) > 235
            if background.sum() == 0:
                fail(name, "page %d: nothing but ink under highlight box %s" % (i, box))
                continue
            tinted = (np.abs(region - plain).sum(axis=2) > 40) & background
            ratio = tinted.sum() / background.sum()
            if ratio < 0.85:
                fail(name, "page %d: only %.0f%% of highlight box %s is tinted when rendered" % (i, ratio * 100, box))
            ink = plain.max(axis=2) < 90
            if ink.sum() == 0:
                fail(name, "page %d: highlight box %s does not cover any text" % (i, box))
            elif (region[ink].max(axis=1) < 140).mean() < 0.9:
                fail(name, "page %d: text under highlight box %s is no longer dark" % (i, box))

    # --- nothing else on the page changed -----------------------------------------------------
    areas = {}
    for item in spec.get("changed_areas", []):
        page_s, box_s = item.split(":")
        areas.setdefault(int(page_s), []).append(tuple(float(v) for v in box_s.split(",")))
    compare = sorted(set(areas) | set(spec.get("unchanged_pages", [])))
    if compare and "original" in spec:
        before_doc = pdfium.PdfDocument(os.path.join(folder, spec["original"]))
        for i in compare:
            before = render(before_doc, i, False)[:, :, :3].astype(np.int32)
            after = render(doc, i, False)[:, :, :3].astype(np.int32)
            if before.shape != after.shape:
                fail(name, "page %d changed size" % i)
                continue
            different = np.abs(before - after).max(axis=2) > 40
            margin = 6 * SCALE
            for (l, t, r, b) in areas.get(i, []):
                y0, y1 = max(int(t * SCALE - margin), 0), int(b * SCALE + margin) + 1
                x0, x1 = max(int(l * SCALE - margin), 0), int(r * SCALE + margin) + 1
                different[y0:y1, x0:x1] = False
            count = int(different.sum())
            allowed = max(24, different.size // 4000)
            if count > allowed:
                ys, xs = np.nonzero(different)
                fail(name, "page %d: %d pixels differ from the original outside the edited area, around (%.0f..%.0f, %.0f..%.0f) pt" % (
                    i, count, xs.min() / SCALE, xs.max() / SCALE, ys.min() / SCALE, ys.max() / SCALE))
        before_doc.close()

    # --- optional renders for looking at ------------------------------------------------------
    if render_dir:
        from PIL import Image
        base = name.replace(os.sep, "__").replace(".pdf", "")
        for i in range(min(len(doc), 8)):
            Image.fromarray(render(doc, i, True)[:, :, :3].astype(np.uint8)).save(
                os.path.join(render_dir, "%s-p%d.png" % (base, i)))
    doc.close()
    mu.close()


def main():
    specs = []
    for dirpath, _, files in os.walk(ROOT):
        for f in sorted(files):
            if f.endswith(".json"):
                specs.append(os.path.join(dirpath, f))
    if not specs:
        print("No artifacts found under", ROOT, "- run the pdfcore unit tests first.")
        sys.exit(2)
    for s in sorted(specs):
        try:
            check(s)
        except Exception as e:  # noqa: BLE001
            fail(os.path.relpath(s, ROOT), "validator crashed: %r" % e)
    print("Cross-validated %d files with qpdf, PDFium, MuPDF%s." % (checked, " and Poppler" if PDFTOTEXT else ""))
    if failures:
        print("%d PROBLEM(S):" % len(failures))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("All independent checks passed.")


if __name__ == "__main__":
    main()
