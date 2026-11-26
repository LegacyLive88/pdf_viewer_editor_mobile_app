package io.github.legacylive88.simplepdfs

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.legacylive88.simplepdfs.doc.DocumentSource
import io.github.legacylive88.simplepdfs.doc.RecentDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** How edits reach the place the document lives ("saved instantly over the same file"). */
@RunWith(RobolectricTestRunner::class)
class DocumentSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(TestDocumentProvider::class.java, TestDocumentProvider.AUTHORITY)
        TestDocumentProvider.writable = true
        TestDocumentProvider.seekable = true
    }

    private fun file(name: String, content: String): File = File(tmp.newFolder(), name).apply { writeText(content) }

    @Test
    fun onlyTheChangedTailIsWrittenWhenTheRemoteCopyIsAsWeLeftIt() {
        val remote = file("doc.pdf", "ORIGINAL-CONTENT|old tail that is long")
        val local = file("new.pdf", "ORIGINAL-CONTENT|new tail")
        val source = DocumentSource(context, Uri.fromFile(remote))
        // Corrupt the prefix of the local file on purpose: if only the tail is written, the
        // remote prefix must survive untouched.
        local.writeText("XXXXXXXX-CONTENT|new tail")
        val kind = source.push(local, unchangedPrefix = 17, remoteLength = remote.length())
        assertEquals(DocumentSource.PushKind.TAIL, kind)
        assertEquals("ORIGINAL-CONTENT|new tail", remote.readText())
    }

    @Test
    fun theWholeFileIsWrittenWhenTheRemoteCopyChangedBehindOurBack() {
        val remote = file("doc.pdf", "SOMETHING ELSE ENTIRELY, AND LONGER THAN BEFORE")
        val local = file("new.pdf", "ORIGINAL-CONTENT|new tail")
        val kind = DocumentSource(context, Uri.fromFile(remote)).push(local, unchangedPrefix = 17, remoteLength = 30)
        assertEquals(DocumentSource.PushKind.WHOLE, kind)
        assertEquals("truncated to the new content", "ORIGINAL-CONTENT|new tail", remote.readText())
    }

    @Test
    fun aShorterDocumentLeavesNoLeftoverBytes() {
        val remote = file("doc.pdf", "ORIGINAL-CONTENT|a very long old tail 0123456789")
        val local = file("new.pdf", "ORIGINAL-CONTENT|short")
        DocumentSource(context, Uri.fromFile(remote)).push(local, 17, remote.length())
        assertEquals("ORIGINAL-CONTENT|short", remote.readText())
    }

    @Test
    fun providersWithoutRandomAccessGetAFullRewrite() {
        val remote = file("doc.pdf", "ORIGINAL-CONTENT|old")
        TestDocumentProvider.file = remote
        TestDocumentProvider.seekable = false
        val local = file("new.pdf", "ORIGINAL-CONTENT|new tail")
        val source = DocumentSource(context, TestDocumentProvider.uri())
        assertTrue(source.isWritable())
        assertEquals(DocumentSource.PushKind.WHOLE, source.push(local, 17, remote.length()))
        assertEquals("ORIGINAL-CONTENT|new tail", remote.readText())
    }

    @Test
    fun aReadOnlyLocationIsDetectedAndWritingToItFailsCleanly() {
        val remote = file("doc.pdf", "ORIGINAL")
        TestDocumentProvider.file = remote
        TestDocumentProvider.writable = false
        TestDocumentProvider.displayName = "Invoice 42.pdf"
        val source = DocumentSource(context, TestDocumentProvider.uri())
        assertFalse(source.isWritable())
        assertEquals("Invoice 42.pdf", source.displayName)
        try {
            source.push(file("new.pdf", "ORIGINAL + more"), 8, 8)
            fail("writing must fail")
        } catch (e: IOException) {
            // expected
        }
        assertEquals("ORIGINAL", remote.readText())
        // reading still works
        val copy = File(tmp.newFolder(), "copy.pdf")
        assertEquals(8L, source.copyTo(copy))
        assertEquals("ORIGINAL", copy.readText())
    }

    @Test
    fun namesComeFromTheProviderOrTheAddress() {
        assertEquals("report.pdf", DocumentSource(context, Uri.fromFile(file("report.pdf", "x"))).displayName)
        assertEquals("document.pdf", DocumentSource(context, Uri.parse("content://nowhere.example")).displayName)
    }

    @Test
    fun recentDocumentsAreKeptNewestFirstWithoutDuplicates() {
        val recents = RecentDocuments(context)
        for (e in recents.list()) recents.remove(e.uri)
        val a = Uri.parse("content://x/a")
        val b = Uri.parse("content://x/b")
        recents.add(a, "A.pdf")
        recents.add(b, "B.pdf")
        recents.add(a, "A.pdf")
        assertEquals(listOf("A.pdf", "B.pdf"), recents.list().map { it.name })
        for (i in 1..12) recents.add(Uri.parse("content://x/n$i"), "N$i.pdf")
        assertEquals("the list is capped", 8, recents.list().size)
        assertEquals("N12.pdf", recents.list().first().name)
        recents.remove(Uri.parse("content://x/n12"))
        assertEquals("N11.pdf", recents.list().first().name)
    }
}
