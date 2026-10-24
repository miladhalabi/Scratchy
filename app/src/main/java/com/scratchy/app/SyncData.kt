package com.scratchy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a note is written inside the encrypted payloads (Drive files and relay messages). */
object NoteJson {
    fun to(n: Note): JSONObject = JSONObject().put("id", n.id).put("title", n.title).put("body", n.body)
        .put("createdAt", n.createdAt).put("updatedAt", n.updatedAt).put("pinned", n.pinned)
        .put("images", JSONArray(n.images)).put("secret", n.secret).put("rev", n.rev)
        .put("category", if (n.secret) "" else n.category)

    fun from(o: JSONObject) = Note(
        o.getString("id"), o.optString("title"), o.optString("body"), o.optLong("createdAt"),
        o.optLong("updatedAt"), o.optBoolean("pinned"),
        o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        o.optBoolean("secret"), o.optLong("rev"), o.optString("category")
    )

    /** One note file: either the note, or a deletion marker. */
    fun side(o: JSONObject): Merge.Side =
        if (o.optBoolean("deleted")) Merge.Side(null, o.optLong("at")) else Merge.Side(from(o))

    fun payload(s: Merge.Side, id: String): JSONObject =
        if (s.note != null) to(s.note) else JSONObject().put("id", id).put("deleted", true).put("at", s.deletedAt)
}

/**
 * What each note looked like the last time this device and Drive agreed: the common ancestor for merging,
 * plus the Drive file version it was read at (so unchanged notes are never downloaded again).
 */
object SyncState {
    class Entry(val base: Note?, val ver: String, val tomb: Long) {
        fun side() = if (base != null) Merge.Side(base) else Merge.Side(null, tomb)
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "sync_state.json")

    @Synchronized
    fun load(ctx: Context): MutableMap<String, Entry> {
        val f = file(ctx)
        val out = HashMap<String, Entry>()
        if (!f.exists()) return out
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return out
        o.keys().forEach { id ->
            val e = o.getJSONObject(id)
            out[id] = Entry(e.optJSONObject("base")?.let(NoteJson::from), e.optString("ver"), e.optLong("tomb"))
        }
        return out
    }

    @Synchronized
    fun save(ctx: Context, m: Map<String, Entry>) {
        val o = JSONObject()
        m.forEach { (id, e) ->
            o.put(id, JSONObject().put("ver", e.ver).put("tomb", e.tomb)
                .also { j -> e.base?.let { j.put("base", NoteJson.to(it)) } })
        }
        val tmp = File(ctx.filesDir, "sync_state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(ctx))
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}
