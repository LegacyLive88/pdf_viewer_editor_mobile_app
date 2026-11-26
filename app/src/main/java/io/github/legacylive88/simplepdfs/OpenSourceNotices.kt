package io.github.legacylive88.simplepdfs

import android.content.res.AssetManager

/**
 * The licence texts of the open-source components the app is built with, for the
 * "Open-source licences" screen.
 *
 * The texts are kept in the repository exactly as their authors publish them
 * (`assets/licenses/THIRD_PARTY_NOTICES.txt`, and `assets/fonts/LICENSE.txt` next to the
 * bundled fonts). They are only re-flowed for display.
 */
object OpenSourceNotices {

    private const val COMPONENTS = "licenses/THIRD_PARTY_NOTICES.txt"
    private const val FONTS = "fonts/LICENSE.txt"

    /** A line at least this long was wrapped by its author because it reached the margin. */
    private const val WRAPPED_LINE_LENGTH = 60

    private const val RULE_LENGTH = 24

    fun load(assets: AssetManager): String = reflow(read(assets, COMPONENTS) + "\n" + read(assets, FONTS))

    private fun read(assets: AssetManager, name: String): String =
        assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Licence files are wrapped at about 78 columns, which on a phone breaks every line a
     * second time. This joins the lines of each paragraph again so the text wraps to the
     * width of the screen. Words and their order are never changed.
     *
     * A line continues the previous one when that line was long enough to have been wrapped
     * and both are indented alike; short lines (headings, names, list items) keep their break.
     */
    fun reflow(text: String): String {
        val lines = ArrayList<StringBuilder>()
        var previousLength = 0
        var previousIndent = -1 // -1: the previous line cannot be continued
        for (raw in text.replace("\r", "").split('\n')) {
            val line = raw.trimEnd()
            val body = line.trimStart()
            val indent = line.length - body.length
            when {
                body.isEmpty() -> {
                    lines.add(StringBuilder())
                    previousIndent = -1
                }
                isRule(body) -> {
                    lines.add(StringBuilder(body.take(RULE_LENGTH)))
                    previousIndent = -1
                }
                previousIndent == indent && previousLength >= WRAPPED_LINE_LENGTH && !startsStatement(body) -> {
                    lines.last().append(' ').append(body)
                    previousLength = line.length
                }
                else -> {
                    lines.add(StringBuilder(body))
                    previousLength = line.length
                    previousIndent = indent
                }
            }
        }
        return lines.joinToString("\n").trim('\n') + "\n"
    }

    private fun isRule(body: String): Boolean =
        body.length >= 10 && (body[0] == '=' || body[0] == '-') && body.all { it == body[0] }

    /** Copyright statements stand on their own line even when the one before is long. */
    private fun startsStatement(body: String): Boolean = body.startsWith("Copyright ")
}
