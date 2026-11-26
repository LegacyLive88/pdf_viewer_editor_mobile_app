package io.github.legacylive88.simplepdfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Open-source licences" screen: the licences of everything the app contains must be in
 * it, and re-flowing them for a phone screen must not change a word.
 * Plain JUnit; the files are read straight from the source tree.
 */
class OpenSourceNoticesTest {

    private val components = File("src/main/assets/licenses/THIRD_PARTY_NOTICES.txt").readText()
    private val fonts = File("../pdfcore/src/main/assets/fonts/LICENSE.txt").readText()
    private val shown = OpenSourceNotices.reflow(components + "\n" + fonts)

    private fun words(text: String): List<String> =
        text.split(Regex("\\s+")).filter { w -> w.isNotEmpty() && !(w.length >= 10 && w.all { it == '=' || it == '-' }) }

    @Test
    fun wrappedLinesAreJoinedAndShortLinesKeepTheirBreak() {
        val source = """
            A HEADING
            =========================================

            This sentence was wrapped by its author because it reached the right
            margin of a terminal, and continues here.
            A short line.
            Another one.

               An indented paragraph that is also long enough to have been wrapped at
               the margin goes on like this.
               1. A list item
               2. Another list item
        """.trimIndent()
        assertEquals(
            "A HEADING\n" +
                "========================\n" +
                "\n" +
                "This sentence was wrapped by its author because it reached the right margin of a terminal, and continues here.\n" +
                "A short line.\n" +
                "Another one.\n" +
                "\n" +
                "An indented paragraph that is also long enough to have been wrapped at the margin goes on like this.\n" +
                "1. A list item\n" +
                "2. Another list item\n",
            OpenSourceNotices.reflow(source),
        )
    }

    @Test
    fun copyrightStatementsAndDifferentlyIndentedLinesAreNeverRunTogether() {
        val source = "Digitized data copyright (c) 2010 Google Corporation with Reserved Font Arimo, Tinos and Cousine.\n" +
            "Copyright (c) 2012 Red Hat, Inc. with Reserved Font Name Liberation.\n" +
            "\n" +
            "   Redistribution and use in source and binary forms, with or without modification, are\n" +
            "      1. indented differently, so a new line\n"
        assertEquals(
            "Digitized data copyright (c) 2010 Google Corporation with Reserved Font Arimo, Tinos and Cousine.\n" +
                "Copyright (c) 2012 Red Hat, Inc. with Reserved Font Name Liberation.\n" +
                "\n" +
                "Redistribution and use in source and binary forms, with or without modification, are\n" +
                "1. indented differently, so a new line\n",
            OpenSourceNotices.reflow(source),
        )
    }

    @Test
    fun reflowingTheRealLicencesChangesNoWord() {
        assertEquals(words(components + "\n" + fonts), words(shown))
        // and it did its job: no paragraph of the Apache licence is still broken mid-sentence
        assertTrue(shown.contains("\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document."))
        assertTrue("windows line ends would end up on screen", !shown.contains('\r'))
    }

    @Test
    fun everyComponentInTheAppIsNamedWithItsLicence() {
        for (required in listOf(
            // the PDF library and what it is built from or bundles
            "PdfBox-Android", "Apache PDFBox", "Apache FontBox", "Apache License", "Version 2.0, January 2004",
            "Adobe Font Metrics", "CMaps for PDF Fonts", "Adobe Glyph List", "Unicode, Inc.",
            "The Legion of the Bouncy Castle",
            // the app framework
            "AndroidX", "Material Components for Android", "Kotlin standard library", "kotlinx.coroutines",
            // the fonts written into edited PDFs
            "Liberation", "SIL OPEN FONT LICENSE Version 1.1", "DejaVu", "Bitstream Vera",
        )) {
            assertTrue("The licences screen does not mention \"$required\"", shown.contains(required))
        }
    }

    /**
     * Guards the list against going stale: every library the build ships must be accounted for.
     * When this fails, add the new library's licence to THIRD_PARTY_NOTICES.txt and its group here.
     */
    @Test
    fun everyLibraryTheBuildShipsIsCoveredByTheNotices() {
        val covered = mapOf(
            "androidx." to "AndroidX",
            "com.google.android.material" to "Material Components for Android",
            "org.jetbrains.kotlinx" to "kotlinx.coroutines",
            "com.tom-roush" to "PdfBox-Android",
        )
        val testOnly = listOf("junit", "org.robolectric", "androidx.test", "org.jetbrains.kotlinx:kotlinx-coroutines-test")
        val catalog = File("../gradle/libs.versions.toml").readText()
        val modules = Regex("module\\s*=\\s*\"([^\"]+)\"").findAll(catalog).map { it.groupValues[1] }.toList()
        assertTrue("the version catalog could not be read", modules.size >= 9)
        var shipped = 0
        for (module in modules) {
            if (testOnly.any { module.startsWith(it) }) continue
            shipped++
            val name = covered.entries.firstOrNull { module.startsWith(it.key) }?.value
            assertTrue("No licence is listed for the library $module", name != null)
            assertTrue("The notices do not mention $name (for $module)", components.contains(name!!))
        }
        assertEquals("libraries shipped in the app", 8, shipped)
        assertFalse("test libraries must not be counted as shipped", modules.none { it.startsWith("junit") })
    }
}
