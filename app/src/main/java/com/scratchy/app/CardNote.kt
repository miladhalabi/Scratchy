package com.scratchy.app

import android.content.Context
import java.util.UUID

/** Which note the floating card opens on. Chosen in Settings: recent / last / scratch / new. */
object CardNote {
    const val RECENT_MS = 30 * 60 * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun mode(ctx: Context) = prefs(ctx).getString("open_mode", "recent") ?: "recent"
    fun setMode(ctx: Context, m: String) = prefs(ctx).edit().putString("open_mode", m).apply()

    /**
     * Remember the note the card last worked on, and when. Picking an old note from the card's list
     * counts as using it now, so it still comes back next time. Blank notes are never remembered.
     */
    fun remember(ctx: Context, id: String) =
        prefs(ctx).edit().putString("lastCardNote", id).putLong("lastCardAt", System.currentTimeMillis()).apply()

    /** The note the card last worked on, however old, or null. */
    fun lastNote(ctx: Context): Note? = lastSaved(ctx)

    /** Secret notes are never offered by the card, so reopening it never shows one. */
    private fun lastSaved(ctx: Context): Note? =
        prefs(ctx).getString("lastCardNote", null)?.let { NoteStore.get(ctx, it) }?.takeIf { !it.secret }

    /** The note to show, and whether it is an existing one (true) or a fresh blank (false). */
    fun pick(ctx: Context): Pair<Note, Boolean> {
        val last = lastSaved(ctx)
        return when (mode(ctx)) {
            "new" -> NoteStore.newNote() to false
            "last" -> last?.let { it to true } ?: (NoteStore.newNote() to false)
            "scratch" -> scratch(ctx) to true
            else -> {   // "recent"
                val usedAt = prefs(ctx).getLong("lastCardAt", last?.updatedAt ?: 0L)
                if (last != null && System.currentTimeMillis() - maxOf(usedAt, last.updatedAt) < RECENT_MS) last to true
                else NoteStore.newNote() to false
            }
        }
    }

    /** The permanent scratch note; created and pinned the first time it is needed. */
    private fun scratch(ctx: Context): Note {
        val p = prefs(ctx)
        p.getString("scratchId", null)?.let { id -> NoteStore.get(ctx, id)?.let { return it } }
        val now = System.currentTimeMillis()
        val n = Note(UUID.randomUUID().toString(), "Scratch", "", now, now, pinned = true)
        NoteStore.save(ctx, n)
        p.edit().putString("scratchId", n.id).apply()
        return n
    }
}
