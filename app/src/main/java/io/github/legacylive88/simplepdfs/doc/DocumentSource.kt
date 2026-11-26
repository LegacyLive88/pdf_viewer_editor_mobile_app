package io.github.legacylive88.simplepdfs.doc

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The place a document really lives: a `content://` (or `file://`) URI handed to the app by the
 * system file picker or by another app.
 *
 * The app never edits this location directly. It works on a private copy and, after every
 * verified edit, mirrors the result back here with [push].
 */
class DocumentSource(context: Context, val uri: Uri) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** File name to show in the title bar and to suggest when saving a copy. */
    val displayName: String by lazy { queryDisplayName() ?: fallbackName() }

    private fun queryDisplayName(): String? = try {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun fallbackName(): String {
        val last = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return last ?: "document.pdf"
    }

    /** Keeps access to the document across app restarts where the provider allows it. */
    fun persistPermissions() {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        for (flags in intArrayOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )) {
            try {
                resolver.takePersistableUriPermission(uri, flags)
                return
            } catch (_: Exception) {
                // not persistable, or not granted with these flags
            }
        }
    }

    /** Copies the whole document into [target]. Returns the number of bytes copied. */
    @Throws(IOException::class)
    fun copyTo(target: File): Long {
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw IOException("No permission to read this file", e)
        } ?: throw IOException("The file could not be opened")
        input.use { src ->
            FileOutputStream(target).use { dst ->
                val n = src.copyTo(dst, 1 shl 16)
                dst.fd.sync()
                return n
            }
        }
    }

    /**
     * Whether edits can be written back to this location. Decided by actually opening the
     * document for writing (without changing it): permission flags alone do not tell.
     */
    fun isWritable(): Boolean {
        for (mode in arrayOf("rw", "wa")) {
            try {
                resolver.openFileDescriptor(uri, mode)?.use { return true }
            } catch (_: Exception) {
                // try the next mode
            }
        }
        return false
    }

    /**
     * Makes the document at [uri] identical to [file].
     *
     * The engine only ever appends to a document, so all of [file] up to [unchangedPrefix] is
     * already in place. If the provider gives a seekable descriptor and the remote copy still
     * has the length we last left it at, only the tail is rewritten – the original bytes of the
     * user's document are never touched, so an interrupted save cannot destroy them. Otherwise
     * the whole file is rewritten.
     *
     * @param remoteLength length the document at [uri] is expected to have now
     * @return how the document was written, for diagnostics and tests
     */
    @Throws(IOException::class)
    fun push(file: File, unchangedPrefix: Long, remoteLength: Long): PushKind {
        if (unchangedPrefix in 1..remoteLength && tryWriteTail(file, unchangedPrefix, remoteLength)) {
            return PushKind.TAIL
        }
        writeWhole(file, remoteLength)
        return PushKind.WHOLE
    }

    enum class PushKind { TAIL, WHOLE }

    private fun tryWriteTail(file: File, unchangedPrefix: Long, remoteLength: Long): Boolean {
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: return false
            pfd.use {
                FileOutputStream(pfd.fileDescriptor).channel.use { out ->
                    if (out.size() != remoteLength) return false // changed behind our back, or not a real file
                    FileInputStream(file).channel.use { src ->
                        val end = src.size()
                        var position = unchangedPrefix
                        out.position(position)
                        while (position < end) {
                            val n = src.transferTo(position, minOf(end - position, CHUNK), out)
                            if (n <= 0) throw IOException("Write stalled")
                            position += n
                        }
                        out.truncate(end)
                        out.force(true)
                    }
                }
            }
            return true
        } catch (_: Exception) {
            // Not seekable, not supported, or failed part-way: the full rewrite below puts
            // every byte in place whatever state this attempt left behind.
            return false
        }
    }

    private fun writeWhole(file: File, remoteLength: Long) {
        var lastError: Exception? = null
        // "wt" truncates. Some providers only know "rwt"; plain "w" may not truncate, which is
        // only safe when the new content is at least as long as the old.
        val modes = if (file.length() >= remoteLength) arrayOf("wt", "rwt", "w") else arrayOf("wt", "rwt")
        for (mode in modes) {
            val stream: OutputStream = try {
                resolver.openOutputStream(uri, mode)
            } catch (e: Exception) {
                lastError = e
                null
            } ?: continue
            stream.use { out ->
                FileInputStream(file).use { it.copyTo(out, 1 shl 16) }
                out.flush()
            }
            return
        }
        throw IOException("This location cannot be written to", lastError)
    }

    private companion object {
        const val CHUNK = 4L * 1024 * 1024
    }
}
