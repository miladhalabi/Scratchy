package com.scratchy.app

import java.util.UUID

/**
 * Pure merge logic for sync (no Android types, so it is unit tested on the JVM).
 *
 * For one note id there are three versions: the [base] both sides last agreed on, [ours] on this phone and
 * [theirs] in Drive. A side is either a note or a deletion (note == null, deletedAt = when).
 */
object Merge {

    class Side(val note: Note?, val deletedAt: Long = 0L) {
        val deleted get() = note == null
    }

    /**
     * [note] is the version both devices should end up with (null = the note is deleted, at [deletedAt]).
     * [conflictCopy] is set when the same lines were changed on both sides: the loser is kept as a new note.
     */
    class Result(val note: Note?, val deletedAt: Long, val conflictCopy: Note? = null)

    /** Which version is newer: higher edit counter first, then timestamp. Ties go to the first argument. */
    fun newer(a: Note, b: Note) = if (compareValuesBy(b, a, { it.rev }, { it.updatedAt }) > 0) b else a

    /**
     * A note change handed to us directly (not read from Drive) can arrive late or out of order.
     * Ignore it when we already hold a later edit of the same note.
     */
    fun staleRelay(local: Note?, theirs: Note?) = local != null && theirs != null && theirs.rev < local.rev

    fun resolve(base: Note?, ours: Side?, theirs: Side?, newId: () -> String = { UUID.randomUUID().toString() }): Result? {
        if (ours == null && theirs == null) return null
        if (theirs == null) return Result(ours!!.note, ours.deletedAt)
        if (ours == null) return Result(theirs.note, theirs.deletedAt)

        val o = ours.note
        val t = theirs.note
        return when {
            o == null && t == null -> Result(null, maxOf(ours.deletedAt, theirs.deletedAt))
            o == null -> {                       // we deleted it, they still have it
                val editedAfter = (base == null || !t!!.sameContent(base)) && t!!.updatedAt > ours.deletedAt
                if (editedAfter) Result(t, 0L) else Result(null, ours.deletedAt)
            }
            t == null -> {                       // they deleted it, we still have it
                val editedAfter = (base == null || !o.sameContent(base)) && o.updatedAt > theirs.deletedAt
                if (editedAfter) Result(o, 0L) else Result(null, theirs.deletedAt)
            }
            else -> both(base, o, t, newId)
        }
    }

    private fun both(base: Note?, o: Note, t: Note, newId: () -> String): Result {
        if (o.sameContent(t)) return Result(newer(o, t).copy(rev = maxOf(o.rev, t.rev), updatedAt = maxOf(o.updatedAt, t.updatedAt)), 0L)
        if (base != null) {
            val oChanged = !o.sameContent(base)
            val tChanged = !t.sameContent(base)
            if (!oChanged) return Result(t, 0L)
            if (!tChanged) return Result(o, 0L)
            merge3(base, o, t)?.let { return Result(it, 0L) }
        }
        // Both changed the same lines (or there is no common base): the newer one stays, the other is kept as a copy.
        val winner = newer(o, t)
        val loser = if (winner === o) t else o
        val images = (winner.images + loser.images).distinct()
        val kept = winner.copy(images = images, rev = maxOf(o.rev, t.rev) + 1, updatedAt = maxOf(o.updatedAt, t.updatedAt))
        val copy = loser.copy(
            id = newId(), title = (loser.title.ifBlank { "Untitled" }) + " (conflict)",
            images = emptyList(), pinned = false, rev = 1, createdAt = loser.updatedAt,
        )
        return Result(kept, 0L, copy)
    }

    /** Merges non-clashing edits. Returns null when both sides changed the same part. */
    private fun merge3(base: Note, o: Note, t: Note): Note? {
        val title = merge1(base.title, o.title, t.title) ?: return null
        val body = diff3(base.body.split("\n"), o.body.split("\n"), t.body.split("\n"))?.joinToString("\n") ?: return null
        val newest = newer(o, t)
        val images = ((o.images + t.images).distinct()).filter { img ->
            // dropped on either side relative to the base means removed
            !(img in base.images && (img !in o.images || img !in t.images))
        }
        val pinned = if (o.pinned == t.pinned) o.pinned else if (o.pinned != base.pinned) o.pinned else t.pinned
        val secret = if (o.secret == t.secret) o.secret else if (o.secret != base.secret) o.secret else t.secret
        val category = if (secret) "" else if (o.category == t.category) o.category
            else if (o.category != base.category) o.category else t.category
        return newest.copy(
            title = title, body = body, images = images, pinned = pinned, secret = secret, category = category,
            rev = maxOf(o.rev, t.rev) + 1, updatedAt = maxOf(o.updatedAt, t.updatedAt),
        )
    }

    private fun merge1(base: String, a: String, b: String): String? = when {
        a == b -> a
        a == base -> b
        b == base -> a
        else -> null
    }

    // ---- line-based three-way merge (diff3) ----

    private class Hunk(val start: Int, val end: Int, val lines: List<String>)    // replaces base[start until end]

    private fun hunks(base: List<String>, other: List<String>): List<Hunk>? {
        val n = base.size; val m = other.size
        if (n.toLong() * m > 4_000_000L) return null                            // too big to diff cheaply
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (base[i] == other[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<Hunk>()
        var i = 0; var j = 0
        var hs = -1; var hj = -1                                                 // start of the current differing run
        fun close(bi: Int, oj: Int) {
            if (hs >= 0) { out += Hunk(hs, bi, other.subList(hj, oj)); hs = -1 }
        }
        while (i < n || j < m) {
            if (i < n && j < m && base[i] == other[j]) { close(i, j); i++; j++ }
            else {
                if (hs < 0) { hs = i; hj = j }
                if (i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1])) i++ else j++
            }
        }
        close(n, m)
        return out
    }

    /** Returns the merged lines, or null on a clash. */
    fun diff3(base: List<String>, ours: List<String>, theirs: List<String>): List<String>? {
        if (ours == theirs) return ours
        if (ours == base) return theirs
        if (theirs == base) return ours
        val a = hunks(base, ours) ?: return null
        val b = hunks(base, theirs) ?: return null
        val out = ArrayList<String>()
        var pos = 0; var i = 0; var j = 0
        fun apply(h: Hunk) { out.addAll(base.subList(pos, h.start)); out.addAll(h.lines); pos = h.end }
        while (i < a.size || j < b.size) {
            val ha = a.getOrNull(i); val hb = b.getOrNull(j)
            val sameSpot = ha != null && hb != null && ha.start == hb.start && (ha.start == ha.end || hb.start == hb.end)
            when {
                hb == null -> { apply(ha!!); i++ }
                ha == null -> { apply(hb); j++ }
                sameSpot || (ha.start < hb.end && hb.start < ha.end) -> {
                    if (ha.start == hb.start && ha.end == hb.end && ha.lines == hb.lines) { apply(ha); i++; j++ }
                    else return null
                }
                ha.end <= hb.start -> { apply(ha); i++ }
                else -> { apply(hb); j++ }
            }
        }
        out.addAll(base.subList(pos, base.size))
        return out
    }
}
