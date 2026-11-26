#!/usr/bin/env python3
"""Generates the PDF fixtures used by the pdfcore tests.

The engine has to cope with PDFs written by many different programs, each with its own habits
(how text is positioned, which font types are used, how pages are structured). This script
builds a small corpus from several independent producers plus hand-written edge cases.

Usage:
    python3 tools/make_fixtures.py [output-dir]

Requirements (dev machine only, nothing here ships in the app):
    pip install reportlab pypdf pikepdf fpdf2 matplotlib playwright
    LibreOffice (soffice), poppler-utils (pdftocairo), a Chromium for Playwright

Fixtures are committed, so the tests do not need any of this. Producers that are missing are
skipped with a warning; the matching tests are skipped when their fixture is absent.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "pdfcore", "src", "test", "resources", "fixtures")
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

PARA1 = [
    "The first paragraph of this document talks about revenue for the",
    "third quarter. Sales grew steadily in every region, and the team",
    "expects the trend to continue into the next financial year without",
    "any major surprises.",
]
PARA2 = [
    "A second paragraph follows after a blank line. It is shorter than",
    "the first one and mentions the well-known risks of forecasting.",
]
BULLETS = ["• Review the budget", "• Hire two engineers", "• Publish the roadmap"]


def out(name):
    return os.path.join(OUT, name)


def warn(msg):
    print("  [skipped] " + msg)


# --------------------------------------------------------------------------------------------
# Minimal raw PDF writer for hand-crafted content streams
# --------------------------------------------------------------------------------------------
class RawPdf:
    def __init__(self):
        self.objects = []  # bytes bodies, 1-based ids

    def add(self, body):
        if isinstance(body, str):
            body = body.encode("latin-1")
        self.objects.append(body)
        return len(self.objects)

    def reserve(self):
        self.objects.append(None)
        return len(self.objects)

    def set(self, obj_id, body):
        if isinstance(body, str):
            body = body.encode("latin-1")
        self.objects[obj_id - 1] = body

    def stream(self, data, extra="", compress=False):
        if isinstance(data, str):
            data = data.encode("latin-1")
        if compress:
            data = zlib.compress(data)
            extra += " /Filter /FlateDecode"
        return self.add(b"<< /Length %d%s >>\nstream\n" % (len(data), extra.encode("latin-1")) + data + b"\nendstream")

    def write(self, path, root_id, trailer_extra=""):
        buf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for i, body in enumerate(self.objects, start=1):
            offsets.append(len(buf))
            buf += b"%d 0 obj\n" % i + body + b"\nendobj\n"
        xref = len(buf)
        buf += b"xref\n0 %d\n" % (len(self.objects) + 1)
        buf += b"0000000000 65535 f \n"
        for off in offsets:
            buf += b"%010d 00000 n \n" % off
        buf += b"trailer\n<< /Size %d /Root %d 0 R%s >>\nstartxref\n%d\n%%%%EOF\n" % (
            len(self.objects) + 1, root_id, trailer_extra.encode("latin-1"), xref)
        with open(path, "wb") as f:
            f.write(buf)


def raw_document(pages, path, fonts=None):
    """pages: list of dicts {content, mediabox, extra (page dict extras), resources (override)}"""
    pdf = RawPdf()
    catalog = pdf.reserve()
    pages_id = pdf.reserve()
    f1 = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    f2 = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    f3 = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>")
    default_res = "<< /Font << /F1 %d 0 R /F2 %d 0 R /F3 %d 0 R >> >>" % (f1, f2, f3)
    kids = []
    for p in pages:
        res = p.get("resources_fn")
        res = res(pdf, f1, f2, f3) if res else default_res
        contents = p["content"]
        if isinstance(contents, (list, tuple)):
            ids = [pdf.stream(c, compress=p.get("compress", False)) for c in contents]
            contents_ref = "[" + " ".join("%d 0 R" % i for i in ids) + "]"
        else:
            contents_ref = "%d 0 R" % pdf.stream(contents, compress=p.get("compress", False))
        mb = p.get("mediabox", "[0 0 612 792]")
        page_id = pdf.add("<< /Type /Page /Parent %d 0 R /MediaBox %s /Resources %s /Contents %s %s >>" % (
            pages_id, mb, res, contents_ref, p.get("extra", "")))
        kids.append(page_id)
    pdf.set(pages_id, "<< /Type /Pages /Count %d /Kids [%s] >>" % (len(kids), " ".join("%d 0 R" % k for k in kids)))
    pdf.set(catalog, "<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
    pdf.write(path, catalog)


def esc(s):
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def para_stream(lines, x, y, size=12, leading=15, font="F1"):
    parts = ["BT", "/%s %g Tf" % (font, size), "%g TL" % leading, "1 0 0 1 %g %g Tm" % (x, y)]
    for i, line in enumerate(lines):
        if i:
            parts.append("T*")
        parts.append("(%s) Tj" % esc(line))
    parts.append("ET")
    return "\n".join(parts) + "\n"


# --------------------------------------------------------------------------------------------
# reportlab
# --------------------------------------------------------------------------------------------
def make_reportlab():
    try:
        from reportlab.lib.enums import TA_JUSTIFY
        from reportlab.lib.pagesizes import letter
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.pdfgen import canvas
        from reportlab.platypus import Frame, PageTemplate, BaseDocTemplate, Paragraph, Spacer
    except ImportError:
        warn("reportlab not installed")
        return

    # basic.pdf ----------------------------------------------------------------------------
    c = canvas.Canvas(out("basic.pdf"), pagesize=letter, invariant=1)
    c.setTitle("Basic fixture")
    c.setFont("Helvetica-Bold", 20)
    c.drawCentredString(306, 720, "Quarterly Report")
    c.setFont("Helvetica", 11)
    c.drawRightString(540, 690, "26 November 2026")
    t = c.beginText(72, 640)
    t.setFont("Helvetica", 12)
    t.setLeading(15)
    for line in PARA1:
        t.textLine(line)
    t.textLine("")
    for line in PARA2:
        t.textLine(line)
    t.textLine("")
    for line in BULLETS:
        t.textLine(line)
    c.drawText(t)
    c.setFont("Helvetica", 9)
    c.drawString(72, 40, "Page 1 of 2")
    c.showPage()
    c.setFont("Helvetica", 12)
    c.drawString(72, 700, "The second page has a single line of text.")
    c.setFont("Helvetica", 9)
    c.drawString(72, 40, "Page 2 of 2")
    c.showPage()
    c.save()

    # justified.pdf ------------------------------------------------------------------------
    body = ParagraphStyle("body", fontName="Times-Roman", fontSize=11, leading=14,
                          alignment=TA_JUSTIFY, firstLineIndent=18, spaceAfter=0)
    head = ParagraphStyle("head", fontName="Times-Bold", fontSize=16, leading=20, spaceAfter=8)
    lorem = ("Justified text stretches the spaces between words so that every line ends at the "
             "same right margin. Editing such a paragraph must keep the neighbouring paragraphs "
             "exactly where they were, and the edited paragraph should be justified again when "
             "it is written back. This sentence makes the paragraph long enough to wrap several "
             "times at the chosen column width.")
    second = ("Paragraphs in this fixture are separated only by a first line indent, which is "
              "how most books are typeset. There is no blank line between them, so the analysis "
              "has to rely on the short last line and the indent to find the boundary.")
    third = "A short final paragraph closes the page."
    doc = BaseDocTemplate(out("justified.pdf"), pagesize=letter, invariant=1,
                          leftMargin=90, rightMargin=90, topMargin=80, bottomMargin=80)
    frame = Frame(90, 80, 432, 632, leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
    doc.addPageTemplates([PageTemplate(id="p", frames=[frame])])
    doc.build([Paragraph("A Justified Chapter", head), Paragraph(lorem, body), Paragraph(second, body),
               Paragraph(third, body)])

    # columns.pdf --------------------------------------------------------------------------
    col = ParagraphStyle("col", fontName="Helvetica", fontSize=10, leading=12, spaceAfter=8)
    left_text = ("The left column is written first. It talks about apples, which are grown in "
                 "orchards and picked in autumn. Apples keep well through the winter months.")
    left_text2 = "A second left paragraph mentions pears, plums and cherries as well."
    right_text = ("The right column is written second. It talks about boats, which sail on "
                  "rivers and seas. Boats need regular care to stay watertight and safe.")
    right_text2 = "A second right paragraph mentions ferries, tugs and barges too."
    doc = BaseDocTemplate(out("columns.pdf"), pagesize=letter, invariant=1)
    f_left = Frame(72, 400, 225, 320, leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
    f_right = Frame(315, 400, 225, 320, leftPadding=0, rightPadding=0, topPadding=0, bottomPadding=0)
    doc.addPageTemplates([PageTemplate(id="two", frames=[f_left, f_right])])
    from reportlab.platypus import FrameBreak
    doc.build([Paragraph(left_text, col), Paragraph(left_text2, col), FrameBreak(),
               Paragraph(right_text, col), Paragraph(right_text2, col)])

    # form_shared.pdf ----------------------------------------------------------------------
    c = canvas.Canvas(out("form_shared.pdf"), pagesize=letter, invariant=1)
    # The inner form's bounding box is 300 x 60; the last line lies below it and is therefore
    # clipped away: it is in the file but no viewer shows it.
    c.beginForm("inner", 0, 0, 300, 60)
    t = c.beginText(0, 40)
    t.setFont("Helvetica", 12)
    t.setLeading(15)
    for line in ["Text inside a nested form object that is", "drawn on more than one page.", "",
                 "", "Hidden line outside the form box."]:
        t.textLine(line)
    c.drawText(t)
    c.endForm()
    c.beginForm("outer")
    c.saveState()
    c.translate(72, 560)
    c.doForm("inner")
    c.restoreState()
    c.setFont("Helvetica", 12)
    c.drawString(72, 520, "Outer form text stays untouched.")
    c.endForm()
    for n in (1, 2):
        c.doForm("outer")
        c.setFont("Helvetica", 12)
        c.drawString(72, 700, "Direct page text on page %d." % n)
        c.showPage()
    c.save()

    # big.pdf ------------------------------------------------------------------------------
    c = canvas.Canvas(out("big.pdf"), pagesize=letter, invariant=1)
    for n in range(1, 301):
        c.setFont("Helvetica-Bold", 14)
        c.drawString(72, 720, "Section %d" % n)
        t = c.beginText(72, 690)
        t.setFont("Helvetica", 11)
        t.setLeading(14)
        for k in range(30):
            t.textLine("Line %d of page %d carries some filler text to make the page realistic." % (k + 1, n))
        c.drawText(t)
        c.showPage()
    c.save()


# --------------------------------------------------------------------------------------------
# Hand-written content streams
# --------------------------------------------------------------------------------------------
def make_raw():
    # rotated.pdf: same content on pages rotated 0/90/180/270, plus crop box and media box offsets
    content = (para_stream(["Rotated page text line one", "and a second line below it."], 72, 700)
               + para_stream(["Bottom marker"], 72, 100))
    raw_document([
        {"content": content},
        {"content": content, "extra": "/Rotate 90"},
        {"content": content, "extra": "/Rotate 180"},
        {"content": content, "extra": "/Rotate 270"},
        {"content": content, "extra": "/Rotate 90 /CropBox [36 60 500 760]"},
        {"content": para_stream(["Offset media box text", "second offset line"], 172, 800),
         "mediabox": "[100 100 712 892]"},
        {"content": content, "extra": "/Rotate -90"},
    ], out("rotated.pdf"))

    # operators.pdf: the less common ways of showing text
    p1 = "\n".join([
        "BT /F1 12 Tf 14 TL 1 0 0 1 72 720 Tm",
        "(Quote operator first line) Tj",
        "(moves to the next line itself) '",
        "2 0.5 (word and char spacing set here) \"",
        "0 Tw 0 Tc",
        "[(Ker) -30 (ned ) 20 (ar) -15 (ray) -250 (text)] TJ",
        "ET",
        "BT /F1 12 Tf 1 0 0 1 72 640 Tm (Stretched) Tj 150 Tz ( wide text) Tj 100 Tz ET",
        "BT /F1 12 Tf 1 0 0 1 72 610 Tm (Energy is mc) Tj 5 Ts /F1 8 Tf (2) Tj 0 Ts /F1 12 Tf ( in this line.) Tj ET",
        # fake bold: the same string twice, 0.3pt apart
        "BT /F1 12 Tf 1 0 0 1 72 580 Tm (Fake bold heading) Tj ET",
        "BT /F1 12 Tf 1 0 0 1 72.3 580 Tm (Fake bold heading) Tj ET",
        # invisible text (as produced by OCR)
        "BT 3 Tr /F1 12 Tf 1 0 0 1 72 550 Tm (Invisible OCR text layer) Tj 0 Tr ET",
        # one string that contains two words very far apart (tab-like)
        "BT /F1 12 Tf 1 0 0 1 72 520 Tm [(Name:) -6000 (Alice)] TJ ET",
        # coloured text
        "BT 0.8 0.1 0.1 rg /F2 12 Tf 1 0 0 1 72 490 Tm (Red bold paragraph line one) Tj",
        "0 -15 Td (red bold line two.) Tj 0 g ET",
        "",
    ])
    # page 2: content drawn through a flipped coordinate system that is never restored
    p2 = "\n".join([
        "1 0 0 -1 0 792 cm",
        "q",
        "BT /F1 12 Tf 1 0 0 -1 72 92 Tm (Flipped coordinate system line one) Tj",
        "1 0 0 -1 72 107 Tm (flipped line two continues here.) Tj ET",
        "q 0.5 0.5 0.5 rg 72 150 200 20 re f",
        "BT /F1 12 Tf 1 0 0 -1 72 200 Tm (Second block inside unbalanced save) Tj ET",
        "",
    ])
    # page 3: several content streams, split in the middle of a text object
    p3a = "BT /F1 12 Tf 15 TL 1 0 0 1 72 720 Tm (Content split across) Tj T* "
    p3b = "(two separate streams.) Tj ET\n" + para_stream(["Another paragraph further", "down the page."], 72, 600)
    # page 4: scaled text matrix and font size 1 (common in some exporters)
    p4 = "\n".join([
        "BT /F3 1 Tf 12 0 0 12 72 720 Tm (Font size one scaled by the matrix) Tj",
        "0 -1.25 TD (continues on a second line here.) Tj ET",
        "q 0.75 0 0 0.75 0 0 cm BT /F1 16 Tf 1 0 0 1 96 800 Tm (Scaled by the CTM to twelve points) Tj ET Q",
        "",
    ])
    raw_document([{"content": p1}, {"content": p2}, {"content": [p3a, p3b]}, {"content": p4, "compress": True}],
                 out("operators.pdf"))

    # scan.pdf: a page with no text at all, and one that only has an invisible text layer
    blank = "0.9 g 72 72 468 648 re f\n"
    ocr = blank + "BT 3 Tr /F1 12 Tf 15 TL 1 0 0 1 90 680 Tm (Scanned letter recognised by OCR) Tj T* (with a second recognised line.) Tj ET\n"
    raw_document([{"content": blank}, {"content": ocr}], out("scan.pdf"))

    # tight.pdf: table-like rows and a centred multi-line block
    rows = []
    y = 700
    for label, value in [("Invoice number", "2026-0042"), ("Customer", "Legacy Trading"), ("Total due", "R 1 250.00")]:
        rows.append("BT /F1 11 Tf 1 0 0 1 72 %d Tm (%s) Tj ET" % (y, esc(label)))
        rows.append("BT /F1 11 Tf 1 0 0 1 300 %d Tm (%s) Tj ET" % (y, esc(value)))
        y -= 18
    centred = []
    for i, (line, x) in enumerate([("A centred block of text", 232.6), ("that has three lines", 244.6), ("of different widths.", 247.3)]):
        centred.append("BT /F1 12 Tf 1 0 0 1 %g %d Tm (%s) Tj ET" % (x, 560 - 15 * i, esc(line)))
    raw_document([{"content": "\n".join(rows + centred) + "\n"}], out("tight.pdf"))


# --------------------------------------------------------------------------------------------
# LibreOffice, Chromium, Cairo
# --------------------------------------------------------------------------------------------
HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Fixture</title>
<style>
 body { font-family: 'Liberation Serif', 'Times New Roman', serif; font-size: 12pt; margin: 0; }
 h1 { font-family: 'Liberation Sans', Arial, sans-serif; font-size: 20pt; }
 p { text-align: justify; margin: 0 0 10pt 0; line-height: 1.3; }
 p.left { text-align: left; }
 td { border: 1px solid #888; padding: 3pt 6pt; font-size: 11pt; }
</style></head><body>
<h1>Annual Members Meeting</h1>
<p>The annual meeting of members was held in the main hall and was attended by more than
two hundred people. The chairperson opened the meeting with a short welcome and then
<b>presented the financial statements</b> for the year, which were adopted without changes.</p>
<p>After the break the discussion turned to the <i>building project</i>. Several members asked
about the timeline and the budget, and the committee promised to publish a detailed plan
before the end of the month so that everybody can comment on it.</p>
<p class="left">Café prices, naïve questions and the résumé of the treasurer were also discussed.</p>
<ul><li>Approve the minutes</li><li>Elect two new trustees</li><li>Set the date of the next meeting</li></ul>
<table><tr><td>Item</td><td>Amount</td></tr><tr><td>Hall hire</td><td>4 500</td></tr><tr><td>Catering</td><td>12 250</td></tr></table>
<p style="page-break-before: always">The second page starts with this paragraph, which is long enough to wrap onto a
second line at normal page width so that it counts as a real paragraph.</p>
</body></html>
"""


def make_libreoffice():
    soffice = shutil.which("soffice") or shutil.which("libreoffice")
    if not soffice:
        warn("LibreOffice not installed")
        return False
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "lo_letter.html")
        with open(src, "w", encoding="utf-8") as f:
            f.write(HTML)
        profile = os.path.join(tmp, "profile")
        cmd = [soffice, "-env:UserInstallation=file://" + profile, "--headless", "--convert-to",
               "pdf:writer_web_pdf_Export", "--outdir", tmp, src]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
        produced = os.path.join(tmp, "lo_letter.pdf")
        if not os.path.exists(produced):
            warn("LibreOffice conversion failed: " + (r.stderr or r.stdout)[-300:])
            return False
        shutil.copy(produced, out("lo_letter.pdf"))
    return True


def make_chromium():
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        warn("playwright (python) not installed, trying node")
        return make_chromium_node()
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page()
        page.set_content(HTML)
        page.pdf(path=out("chrome_article.pdf"), format="A4",
                 margin={"top": "25mm", "bottom": "25mm", "left": "25mm", "right": "25mm"})
        browser.close()
    return True


def make_chromium_node():
    node = shutil.which("node")
    if not node:
        warn("node not installed")
        return False
    with tempfile.TemporaryDirectory() as tmp:
        html = os.path.join(tmp, "page.html")
        with open(html, "w", encoding="utf-8") as f:
            f.write(HTML)
        script = os.path.join(tmp, "print.js")
        with open(script, "w") as f:
            f.write("""
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('file://' + process.argv[2]);
  await page.pdf({ path: process.argv[3], format: 'A4',
    margin: { top: '25mm', bottom: '25mm', left: '25mm', right: '25mm' } });
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
""")
        r = subprocess.run([node, script, html, out("chrome_article.pdf")], capture_output=True, text=True, timeout=180)
        if r.returncode != 0:
            warn("Chromium print failed: " + r.stderr[-300:])
            return False
    return True


def make_cairo():
    tool = shutil.which("pdftocairo")
    src = out("lo_letter.pdf")
    if not tool or not os.path.exists(src):
        warn("pdftocairo or its input not available")
        return
    subprocess.run([tool, "-pdf", src, out("cairo.pdf")], check=False, timeout=120)


# --------------------------------------------------------------------------------------------
# fpdf2 (embedded TrueType, Identity-H) and matplotlib (Type 3 fonts)
# --------------------------------------------------------------------------------------------
def find_font(name):
    for root in ("/usr/share/fonts", os.path.expanduser("~/.fonts")):
        for dirpath, _, files in os.walk(root):
            if name in files:
                return os.path.join(dirpath, name)
    return None


def make_fpdf():
    try:
        from fpdf import FPDF
    except ImportError:
        warn("fpdf2 not installed")
        return
    font = find_font("DejaVuSans.ttf")
    if not font:
        warn("DejaVuSans.ttf not found")
        return
    pdf = FPDF(format="A4")
    pdf.set_compression(True)
    pdf.add_page()
    pdf.add_font("DejaVu", "", font)
    pdf.set_font("DejaVu", size=12)
    pdf.set_xy(25, 30)
    pdf.multi_cell(160, 6, "Unicode text: élève, über, Łódź and ñandú are written with an embedded "
                           "TrueType font, so every glyph has a proper Unicode mapping in the file.")
    pdf.set_xy(25, 60)
    pdf.multi_cell(160, 6, "Греческий и кириллица: αβγδ – привет мир.")
    pdf.output(out("fpdf_unicode.pdf"))


def make_matplotlib():
    try:
        import matplotlib
        matplotlib.use("pdf")
        import matplotlib.pyplot as plt
    except ImportError:
        warn("matplotlib not installed")
        return
    matplotlib.rcParams["pdf.fonttype"] = 3  # Type 3 fonts: glyphs are little drawing procedures
    matplotlib.rcParams["svg.hashsalt"] = "fixture"
    fig = plt.figure(figsize=(8.5, 11))
    fig.text(0.1, 0.9, "Chart title in a Type 3 font", fontsize=16)
    fig.text(0.1, 0.85, "A caption line drawn with glyph procedures.", fontsize=11)
    ax = fig.add_axes([0.15, 0.4, 0.7, 0.35])
    ax.plot([1, 2, 3, 4], [1, 4, 2, 5])
    ax.set_xlabel("Quarter")
    ax.set_ylabel("Revenue")
    fig.savefig(out("matplotlib_type3.pdf"), metadata={"CreationDate": None, "ModDate": None})
    plt.close(fig)


# --------------------------------------------------------------------------------------------
# Structural variants made from basic.pdf with qpdf (pikepdf)
# --------------------------------------------------------------------------------------------
def make_variants():
    try:
        import pikepdf
    except ImportError:
        warn("pikepdf not installed")
        return
    src = out("basic.pdf")
    if not os.path.exists(src):
        warn("basic.pdf missing")
        return

    with pikepdf.open(src) as pdf:
        pdf.save(out("xref_stream.pdf"), object_stream_mode=pikepdf.ObjectStreamMode.generate,
                 static_id=True, deterministic_id=False)
    with pikepdf.open(src) as pdf:
        pdf.save(out("linearized.pdf"), linearize=True, static_id=True)
    with pikepdf.open(src) as pdf:
        pdf.save(out("encrypted_owner.pdf"), static_id=True,
                 encryption=pikepdf.Encryption(owner="owner-secret", user="", R=6,
                                               allow=pikepdf.Permissions(extract=False, modify_other=False)))
    with pikepdf.open(src) as pdf:
        pdf.save(out("encrypted_user.pdf"), static_id=True,
                 encryption=pikepdf.Encryption(owner="owner-secret", user="open-sesame", R=6))

    # annotated.pdf: highlights written by "another application", in assorted styles
    with pikepdf.open(src) as pdf:
        page = pdf.pages[0]
        A = pikepdf.Array
        N = pikepdf.Name

        def annot(subtype, rect, quads=None, color=(1, 1, 0), extra=None):
            d = pikepdf.Dictionary(Type=N.Annot, Subtype=N("/" + subtype), Rect=A(rect), C=A(color), F=4)
            if quads is not None:
                d.QuadPoints = A(quads)
            if extra:
                for k, v in extra.items():
                    d[k] = v
            return pdf.make_indirect(d)

        # first line of paragraph 1 sits on baseline 640, Helvetica 12
        top, bottom = 651.0, 637.0
        annots = [
            # standard order: TL TR BL BR, covering "The first paragraph" (which ends at x = 173.4)
            annot("Highlight", [72, bottom, 174, top], [72, top, 174, top, 72, bottom, 174, bottom]),
            # ring order (BL BR TR TL) as the specification text literally says, second line
            annot("Highlight", [72, bottom - 15, 250, top - 15],
                  [72, bottom - 15, 250, bottom - 15, 250, top - 15, 72, top - 15], color=(0.5, 1, 0.5)),
            # no QuadPoints at all: only a Rect, third line
            annot("Highlight", [72, bottom - 30, 200, top - 30], None, color=(1, 0.6, 0.8)),
            # other markup kinds must be left alone
            annot("Underline", [72, bottom - 45, 160, top - 45],
                  [72, top - 45, 160, top - 45, 72, bottom - 45, 160, bottom - 45], color=(1, 0, 0)),
            # hidden annotation must not be shown
            annot("Highlight", [300, 300, 400, 320], [300, 320, 400, 320, 300, 300, 400, 300],
                  extra={"/F": 2}),
        ]
        page.Annots = pdf.make_indirect(A(annots))  # indirect array, as many producers write it
        pdf.save(out("annotated.pdf"), static_id=True)

    # incremental.pdf: a file that already carries an incremental update
    make_incremental(src, out("incremental.pdf"))


def make_incremental(src, dst):
    """Appends a hand-written incremental update (a new Info dictionary) to a classic-xref PDF."""
    import re
    data = open(src, "rb").read()
    m = re.search(rb"startxref\s+(\d+)\s+%%EOF\s*$", data)
    size = re.findall(rb"/Size\s+(\d+)", data)
    root = re.findall(rb"/Root\s+(\d+)\s+(\d+)\s+R", data)
    if not m or not size or not root:
        warn("cannot build incremental.pdf from " + src)
        return
    prev = int(m.group(1))
    new_id = int(size[-1])
    buf = bytearray(data)
    if not buf.endswith(b"\n"):
        buf += b"\n"
    offset = len(buf)
    buf += b"%d 0 obj\n<< /Title (Basic fixture) /Subject (updated incrementally) >>\nendobj\n" % new_id
    xref = len(buf)
    buf += b"xref\n%d 1\n%010d 00000 n \n" % (new_id, offset)
    buf += b"trailer\n<< /Size %d /Root %d %d R /Info %d 0 R /Prev %d >>\nstartxref\n%d\n%%%%EOF\n" % (
        new_id + 1, int(root[-1][0]), int(root[-1][1]), new_id, prev, xref)
    with open(dst, "wb") as f:
        f.write(buf)


def main():
    print("Writing fixtures to", OUT)
    make_reportlab()
    make_raw()
    if make_libreoffice():
        make_cairo()
    make_chromium()
    make_fpdf()
    make_matplotlib()
    make_variants()
    for name in sorted(os.listdir(OUT)):
        if name.endswith(".pdf"):
            print("  %-24s %8d bytes" % (name, os.path.getsize(os.path.join(OUT, name))))


if __name__ == "__main__":
    main()
