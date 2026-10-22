package com.scratchy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val images: List<String> = emptyList(),
) {
    val isBlank get() = title.isBlank() && body.isBlank() && images.isEmpty()

    /** Title, or the first non-blank body line when there is no title. */
    val heading: String
        get() = title.trim().ifEmpty { body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "" }

    /** Text shown under the heading in lists. */
    val preview: String
        get() = if (title.isNotBlank()) body.trim()
        else body.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
}

/** Local JSON storage. Small and synchronous on purpose; swap for Room when notes grow. */
object NoteStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "notes.json")

    fun newNote(): Note {
        val now = System.currentTimeMillis()
        return Note(UUID.randomUUID().toString(), "", "", now, now)
    }

    @Synchronized
    fun all(ctx: Context): List<Note> {
        migrate(ctx)
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(
                o.getString("id"), o.optString("title"), o.optString("body"),
                o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("pinned"),
                o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList()
            )
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun search(ctx: Context, query: String): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return all(ctx)
        return all(ctx).filter {
            it.title.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true)
        }
    }

    /** Saves a note; a note that became empty is removed instead of kept as a blank card. */
    @Synchronized
    fun save(ctx: Context, note: Note) {
        val others = all(ctx).filter { it.id != note.id }
        write(ctx, if (note.isBlank) others else others + note)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val all = all(ctx)
        all.firstOrNull { it.id == id }?.images?.forEach { Attachments.delete(ctx, it) }
        write(ctx, all.filter { it.id != id })
    }

    /** Removes the note but keeps its image files so a delete can be undone; call [purge] to finish it. */
    @Synchronized
    fun remove(ctx: Context, id: String) = write(ctx, all(ctx).filter { it.id != id })

    fun purge(ctx: Context, note: Note) = note.images.forEach { Attachments.delete(ctx, it) }

    private fun write(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body)
                .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("pinned", it.pinned)
                .put("images", JSONArray(it.images)))
        }
        val tmp = File(ctx.filesDir, "notes.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** One-time import of the spike's ideas.txt (one line per idea). */
    private fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "ideas.txt")
        if (!old.exists() || file(ctx).exists()) return
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val notes = old.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("\t", limit = 2)
            val ts = runCatching { fmt.parse(parts[0])!!.time }.getOrDefault(System.currentTimeMillis())
            Note(UUID.randomUUID().toString(), "", parts.getOrElse(1) { "" }, ts, ts)
        }
        write(ctx, notes)
        old.renameTo(File(ctx.filesDir, "ideas.txt.migrated"))
    }
}

fun formatWhen(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val d = Date(ms)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(d)
    }
}
