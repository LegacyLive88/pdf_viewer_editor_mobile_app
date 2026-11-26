package io.github.legacylive88.simplepdfs.pdfcore

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Not an assertion test: writes a readable dump of how every fixture is segmented into
 * paragraphs to `build/test-artifacts/dumps/`. Used when tuning the layout analysis.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutDumpTest : PdfTestBase() {

    @Test
    fun dumpAllFixtures() {
        val names = listOf(
            "basic.pdf", "justified.pdf", "columns.pdf", "form_shared.pdf", "rotated.pdf", "operators.pdf",
            "scan.pdf", "tight.pdf", "lo_letter.pdf", "chrome_article.pdf", "cairo.pdf", "fpdf_unicode.pdf",
            "matplotlib_type3.pdf", "xref_stream.pdf", "linearized.pdf", "annotated.pdf", "incremental.pdf",
            "encrypted_owner.pdf",
        )
        val dir = File(System.getProperty("pdfcore.artifacts") ?: "build/test-artifacts", "dumps")
        dir.mkdirs()
        for (name in names) {
            if (!fixtureExists(name)) continue
            val sb = StringBuilder()
            try {
                val s = open(name)
                val pages = minOf(s.pageCount, 8)
                sb.append("$name: ${s.pageCount} pages, encrypted=${s.capabilities.encrypted}\n")
                for (p in 0 until pages) {
                    val size = s.pageSize(p)
                    sb.append("--- page $p  ${size.width} x ${size.height}\n")
                    sb.append(s.debugDump(p))
                    val m = s.markups(p)
                    if (m.isNotEmpty()) sb.append("markups: " + m.joinToString { "${it.kind}#${Integer.toHexString(it.colorRgb)}x${it.quads.size}" } + "\n")
                }
            } catch (e: Exception) {
                sb.append("FAILED: $e\n")
                sb.append(e.stackTraceToString())
            }
            File(dir, name.removeSuffix(".pdf") + ".txt").writeText(sb.toString())
        }
    }
}
