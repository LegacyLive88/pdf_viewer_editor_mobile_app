package io.github.legacylive88.simplepdfs.doc

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * The short list of documents shown on the start screen, most recent first.
 * Only the address and name of each document are remembered, never its content.
 */
class RecentDocuments(context: Context) {

    data class Entry(val uri: Uri, val name: String, val openedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Entry(uri.toUri(), o.optString("name", "document.pdf"), o.optLong("at", 0L))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(uri: Uri, name: String) {
        val entries = ArrayList<Entry>()
        entries.add(Entry(uri, name, System.currentTimeMillis()))
        for (e in list()) if (e.uri != uri && entries.size < MAX) entries.add(e)
        save(entries)
    }

    fun remove(uri: Uri) = save(list().filter { it.uri != uri })

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().put("uri", e.uri.toString()).put("name", e.name).put("at", e.openedAt))
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 8
    }
}
