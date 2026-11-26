package io.github.legacylive88.simplepdfs.pdfcore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import java.io.File

/**
 * Shared plumbing for the engine tests.
 *
 * Every PDF a test writes is also copied to `pdfcore/build/test-artifacts/<Class>/<test>/`
 * together with a small JSON file describing what must be true about it. Those artifacts are
 * then checked by `tools/cross_validate.py` with PDF implementations that share no code with
 * the engine (PDFium, MuPDF, qpdf, Poppler). See docs/TESTING.md.
 */
abstract class PdfTestBase {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val testName = TestName()

    private val sessions = ArrayList<PdfSession>()
    private val expectations = ArrayList<String>()

    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Verifier used in tests: the written file must be loadable by a fresh PDFBox instance. */
    protected val reloadVerifier = SaveVerifier { candidate, previous, expectedPageCount, changes ->
        check(previous.isFile && candidate.length() > 0) { "verifier must be given both files" }
        PDDocument.load(candidate).use { doc ->
            check(doc.numberOfPages == expectedPageCount) { "page count ${doc.numberOfPages} != $expectedPageCount" }
            for (c in changes) {
                check(c.area.isNotEmpty()) { "an edit must say where it changed the page" }
                doc.getPage(c.pageIndex).contents.use { it.readBytes() }
            }
        }
    }

    protected fun fixtureExists(name: String): Boolean =
        javaClass.classLoader!!.getResource("fixtures/$name") != null

    /** Copies a fixture to a private temp file. Skips the test if the fixture was not generated. */
    protected fun fixture(name: String): File {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
        Assume.assumeTrue("fixture $name is not available", stream != null)
        val dir = tmp.newFolder()
        val file = File(dir, name)
        stream!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    protected fun open(name: String, verifier: SaveVerifier? = reloadVerifier): PdfSession = open(fixture(name), verifier)

    protected fun open(file: File, verifier: SaveVerifier? = reloadVerifier): PdfSession {
        val session = PdfSession.open(context, file, File(file.parentFile, "work-${sessions.size}"), verifier)
        sessions.add(session)
        return session
    }

    /** Re-opens a written file in a brand new session, as the app would on the next launch. */
    protected fun reopen(file: File): PdfSession {
        val copy = File(tmp.newFolder(), "reopened.pdf")
        file.copyTo(copy)
        return open(copy)
    }

    private fun artifactDir(): File {
        val root = File(System.getProperty("pdfcore.artifacts") ?: "build/test-artifacts")
        val dir = File(File(root, javaClass.simpleName), testName.methodName.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
        dir.mkdirs()
        return dir
    }

    /**
     * Publishes [file] for cross validation.
     *
     * @param mustContain per page index: strings an independent text extractor must find
     * @param mustNotContain per page index: strings that must be gone
     * @param highlights expected highlight boxes as `page:left,top,right,bottom` in display space
     * @param untouchedPrefixOf if set, the artifact must start with exactly this file's bytes
     * @param changedAreas `page:left,top,right,bottom` boxes (display space) where an edit put or
     *   removed text; rendered, the page must be identical to the original everywhere else
     * @param unchangedPages pages that must render identically to the original
     */
    protected fun publish(
        label: String,
        file: File,
        pages: Int,
        mustContain: Map<Int, List<String>> = emptyMap(),
        mustNotContain: Map<Int, List<String>> = emptyMap(),
        highlights: List<String> = emptyList(),
        highlightCount: Map<Int, Int> = emptyMap(),
        untouchedPrefixOf: File? = null,
        changedAreas: List<String> = emptyList(),
        unchangedPages: List<Int> = emptyList(),
    ) {
        val dir = artifactDir()
        val target = File(dir, "$label.pdf")
        file.copyTo(target, overwrite = true)
        if (untouchedPrefixOf != null) untouchedPrefixOf.copyTo(File(dir, "$label.original.pdf"), overwrite = true)

        fun esc(s: String) = buildString {
            for (ch in s) when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch.code < 0x20 || ch.code > 0x7e -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        fun mapJson(m: Map<Int, List<String>>) = m.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + v.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
        }
        val json = buildString {
            append("{\"file\":\"${esc(target.name)}\",\"pages\":$pages")
            append(",\"must_contain\":${mapJson(mustContain)}")
            append(",\"must_not_contain\":${mapJson(mustNotContain)}")
            append(",\"highlights\":" + highlights.joinToString(",", "[", "]") { "\"${esc(it)}\"" })
            append(",\"highlight_count\":" + highlightCount.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
            append(",\"changed_areas\":" + changedAreas.joinToString(",", "[", "]") { "\"${esc(it)}\"" })
            append(",\"unchanged_pages\":" + unchangedPages.joinToString(",", "[", "]"))
            if (untouchedPrefixOf != null) append(",\"original\":\"${esc("$label.original.pdf")}\"")
            append("}")
        }
        File(dir, "$label.json").writeText(json)
        expectations.add(json)
    }

    @After
    fun closeSessions() {
        for (s in sessions) try {
            s.close()
        } catch (_: Exception) {
        }
        sessions.clear()
    }

    /** Centre of the [occurrence]-th word equal to [word] on the page. */
    protected fun pointOf(session: PdfSession, page: Int, word: String, occurrence: Int = 0): PdfPoint {
        val hits = session.wordBoxes(page).filter { it.first == word }
        if (occurrence >= hits.size) {
            throw AssertionError("word '$word' #$occurrence not found on page $page; words: " +
                session.wordBoxes(page).joinToString(" ") { it.first })
        }
        val q = hits[occurrence].second
        return PdfPoint((q.x1 + q.x3) / 2f, (q.y1 + q.y3) / 2f)
    }

    protected fun selectWords(session: PdfSession, page: Int, from: String, to: String, fromIndex: Int = 0, toIndex: Int = 0): TextSelection =
        session.select(page, pointOf(session, page, from, fromIndex), pointOf(session, page, to, toIndex))
            ?: throw AssertionError("nothing selected")

    /** The paragraph that contains the first occurrence of [word]. */
    protected fun paragraphWith(session: PdfSession, page: Int, word: String, occurrence: Int = 0): ParagraphInfo =
        session.paragraphAt(page, pointOf(session, page, word, occurrence))
            ?: throw AssertionError("no paragraph at '$word'")

    /** The line boxes of paragraphs as `page:left,top,right,bottom` strings, for [publish]. */
    protected fun areasOf(vararg paragraphs: ParagraphInfo): List<String> =
        paragraphs.flatMap { p -> p.quads.map { "${p.pageIndex}:${quadBox(it)}" } }

    protected fun quadBox(q: Quad) = String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f", q.left, q.top, q.right, q.bottom)

    protected fun sameBytesPrefix(original: File, updated: File): Boolean {
        val a = original.readBytes()
        val b = updated.readBytes()
        if (b.size < a.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }
}
