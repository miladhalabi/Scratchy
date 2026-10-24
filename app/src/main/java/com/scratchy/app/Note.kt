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
    val secret: Boolean = false,
    /** Counts edits. Sync orders versions by this first, so a phone with a wrong clock can't win. */
    val rev: Long = 0,
    /** Name of the category, "" = none. Never set on secret notes, so no name leaks from the vault. */
    val category: String = "",
) {
    val isBlank get() = title.isBlank() && body.isBlank() && images.isEmpty()

    /** Same visible content and flags (ignores timestamps and the edit counter). */
    fun sameContent(o: Note) = title == o.title && body == o.body && images == o.images &&
        pinned == o.pinned && secret == o.secret && category == o.category

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
            val secret = o.optBoolean("secret")
            var title = o.optString("title"); var body = o.optString("body")
            if (secret) {   // secret text is stored encrypted
                val text = Vault.decrypt(o.optString("enc"))?.let { runCatching { JSONObject(it) }.getOrNull() }
                title = text?.optString("title") ?: ""; body = text?.optString("body") ?: "(unreadable)"
            }
            Note(
                o.getString("id"), title, body,
                o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("pinned"),
                o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList(),
                secret, o.optLong("rev"), if (secret) "" else o.optString("category")
            )
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    /** Notes for the normal list (secret = false) or for the vault (secret = true). Never mixed. */
    fun list(ctx: Context, secret: Boolean = false) = all(ctx).filter { it.secret == secret }

    fun search(ctx: Context, query: String, secret: Boolean = false): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return list(ctx, secret)
        return list(ctx, secret).filter {
            it.title.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true) ||
                it.category.contains(q, ignoreCase = true)
        }
    }

    /** Saves a note; a note that became empty is removed instead of kept as a blank card. */
    @Synchronized
    fun save(ctx: Context, note: Note) {
        val existing = all(ctx)
        val others = existing.filter { it.id != note.id }
        val prev = existing.firstOrNull { it.id == note.id }
        val bumped = when {
            prev == null -> note.copy(rev = maxOf(note.rev, 1L))
            !prev.sameContent(note) -> note.copy(rev = maxOf(prev.rev, note.rev) + 1)
            else -> note.copy(rev = maxOf(prev.rev, note.rev))
        }
        if (note.isBlank) { if (existing.size != others.size) tombstone(ctx, note.id) }
        else clearTombstone(ctx, note.id)
        write(ctx, if (note.isBlank) others else others + bumped)
        Sync.noteChanged(ctx, note.id)
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val all = all(ctx)
        all.firstOrNull { it.id == id }?.images?.forEach { Attachments.delete(ctx, it) }
        tombstone(ctx, id)
        write(ctx, all.filter { it.id != id })
        Sync.noteChanged(ctx, id)
    }

    /** Removes the note but keeps its image files so a delete can be undone; call [purge] to finish it. */
    @Synchronized
    fun remove(ctx: Context, id: String) {
        tombstone(ctx, id)
        write(ctx, all(ctx).filter { it.id != id })
        Sync.noteChanged(ctx, id)
    }

    fun purge(ctx: Context, note: Note) = note.images.forEach { Attachments.delete(ctx, it) }

    // ---- deletions, remembered so a sync doesn't bring a deleted note back ----

    private fun tombFile(ctx: Context) = File(ctx.filesDir, "deleted.json")

    @Synchronized
    fun tombstones(ctx: Context): Map<String, Long> {
        val f = tombFile(ctx)
        if (!f.exists()) return emptyMap()
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    private fun writeTombstones(ctx: Context, t: Map<String, Long>) {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val o = JSONObject()
        t.forEach { (k, v) -> if (v > cutoff) o.put(k, v) }
        tombFile(ctx).writeText(o.toString())
    }

    private fun tombstone(ctx: Context, id: String) =
        writeTombstones(ctx, tombstones(ctx) + (id to System.currentTimeMillis()))

    private fun clearTombstone(ctx: Context, id: String) {
        val t = tombstones(ctx)
        if (id in t) writeTombstones(ctx, t - id)
    }

    /**
     * Applies what a sync decided, note by note. A note is only touched if it is still exactly as the sync
     * saw it ([seenRev], -1 = absent), so an edit made while the sync was running is never overwritten.
     * Returns the ids that were skipped for that reason. Does not trigger another sync.
     */
    @Synchronized
    fun applySynced(
        ctx: Context, puts: Map<String, Note>, removes: Map<String, Long>, seenRev: Map<String, Long>,
    ): Set<String> {
        val cur = all(ctx).associateBy { it.id }.toMutableMap()
        val tombs = tombstones(ctx).toMutableMap()
        val skipped = HashSet<String>()
        fun still(id: String) = (cur[id]?.rev ?: -1L) == (seenRev[id] ?: -1L)
        puts.forEach { (id, n) -> if (still(id)) { cur[id] = n; tombs.remove(id) } else skipped += id }
        removes.forEach { (id, at) ->
            if (still(id)) {
                cur.remove(id)
                tombs[id] = at
            } else skipped += id
        }
        write(ctx, cur.values.toList())
        writeTombstones(ctx, tombs)
        return skipped
    }

    private fun write(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach {
            val o = JSONObject().put("id", it.id)
                .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("pinned", it.pinned)
                .put("images", JSONArray(it.images)).put("secret", it.secret).put("rev", it.rev)
            if (it.secret) o.put("enc", Vault.encrypt(JSONObject().put("title", it.title).put("body", it.body).toString()))
            else o.put("title", it.title).put("body", it.body).put("category", it.category)
            arr.put(o)
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
