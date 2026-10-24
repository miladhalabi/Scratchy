package com.scratchy.app

import org.junit.Assert.*
import org.junit.Test

class MergeTest {
    private fun note(body: String, title: String = "T", rev: Long = 1, at: Long = 100, images: List<String> = emptyList(),
                     pinned: Boolean = false, id: String = "n1") =
        Note(id, title, body, 1, at, pinned, images, false, rev)

    private fun side(n: Note) = Merge.Side(n)
    private val gone = { t: Long -> Merge.Side(null, t) }

    // ---- diff3 ----

    @Test fun diff3_edits_on_different_lines_merge() {
        val r = Merge.diff3(listOf("a", "b", "c", "d"), listOf("A", "b", "c", "d"), listOf("a", "b", "c", "D"))
        assertEquals(listOf("A", "b", "c", "D"), r)
    }

    @Test fun diff3_same_line_changed_differently_clashes() {
        assertNull(Merge.diff3(listOf("a", "b"), listOf("a", "X"), listOf("a", "Y")))
    }

    @Test fun diff3_same_change_on_both_is_fine() {
        assertEquals(listOf("a", "X"), Merge.diff3(listOf("a", "b"), listOf("a", "X"), listOf("a", "X")))
    }

    @Test fun diff3_insertions_at_opposite_ends() {
        val r = Merge.diff3(listOf("m"), listOf("top", "m"), listOf("m", "bottom"))
        assertEquals(listOf("top", "m", "bottom"), r)
    }

    @Test fun diff3_insert_at_same_spot_clashes() {
        assertNull(Merge.diff3(listOf("a", "b"), listOf("a", "X", "b"), listOf("a", "Y", "b")))
    }

    @Test fun diff3_one_side_deletes_other_edits_elsewhere() {
        val r = Merge.diff3(listOf("a", "b", "c"), listOf("a", "c"), listOf("a", "b", "C"))
        assertEquals(listOf("a", "C"), r)
    }

    // ---- resolve ----

    @Test fun only_one_side_changed_takes_that_side() {
        val base = note("one", rev = 1)
        val r = Merge.resolve(base, side(note("one", rev = 1)), side(note("two", rev = 2)))!!
        assertEquals("two", r.note!!.body); assertNull(r.conflictCopy)
    }

    @Test fun both_edited_different_lines_merge_without_copy() {
        val base = note("a\nb\nc")
        val r = Merge.resolve(base, side(note("A\nb\nc", rev = 2, at = 200)), side(note("a\nb\nC", rev = 2, at = 210)))!!
        assertEquals("A\nb\nC", r.note!!.body)
        assertNull(r.conflictCopy)
        assertEquals(3L, r.note!!.rev)
    }

    @Test fun clashing_edits_keep_newer_and_make_a_conflict_copy() {
        val base = note("a\nb")
        val ours = note("a\nOURS", rev = 2, at = 200)
        val theirs = note("a\nTHEIRS", rev = 3, at = 150)          // fewer minutes but higher rev wins
        val r = Merge.resolve(base, side(ours), side(theirs), newId = { "copy1" })!!
        assertEquals("a\nTHEIRS", r.note!!.body)
        assertEquals("a\nOURS", r.conflictCopy!!.body)
        assertEquals("copy1", r.conflictCopy!!.id)
        assertTrue(r.conflictCopy!!.title.endsWith("(conflict)"))
    }

    @Test fun clock_skew_does_not_beat_a_higher_revision() {
        // Device B's clock is an hour slow, but it edited later (higher rev): its edit must win.
        val base = note("x")
        val a = note("from A", rev = 2, at = 10_000_000)
        val b = note("from B", rev = 3, at = 10_000_000 - 3_600_000)
        val r = Merge.resolve(base, side(a), side(b))!!
        assertEquals("from B", r.note!!.body)
    }

    @Test fun without_a_base_nothing_is_lost() {
        val r = Merge.resolve(null, side(note("mine", rev = 2)), side(note("theirs", rev = 3)))!!
        assertEquals("theirs", r.note!!.body)
        assertEquals("mine", r.conflictCopy!!.body)
    }

    @Test fun images_are_never_lost_on_a_conflict() {
        val base = note("a", images = listOf("i1"))
        val ours = note("OURS", rev = 2, images = listOf("i1", "i2"))
        val theirs = note("THEIRS", rev = 3, images = listOf("i1", "i3"))
        val r = Merge.resolve(base, side(ours), side(theirs))!!
        assertEquals(setOf("i1", "i2", "i3"), r.note!!.images.toSet())
        assertTrue(r.conflictCopy!!.images.isEmpty())             // the copy must not share files that get deleted with it
    }

    @Test fun merged_images_respect_removals() {
        val base = note("t\nm", images = listOf("i1", "i2"))
        val ours = note("T\nm", rev = 2, images = listOf("i1"))                   // removed i2
        val theirs = note("t\nM", rev = 2, images = listOf("i1", "i2", "i3"))     // added i3
        val r = Merge.resolve(base, side(ours), side(theirs))!!
        assertEquals(setOf("i1", "i3"), r.note!!.images.toSet())
    }

    @Test fun merging_twice_changes_nothing() {
        val base = note("a\nb\nc")
        val first = Merge.resolve(base, side(note("A\nb\nc", rev = 2)), side(note("a\nb\nC", rev = 2)))!!.note!!
        val again = Merge.resolve(first, side(first), side(first))!!
        assertEquals(first.body, again.note!!.body)
        assertEquals(first.rev, again.note!!.rev)
        assertNull(again.conflictCopy)
    }

    // ---- deletions ----

    @Test fun delete_beats_an_older_edit() {
        val base = note("x", rev = 1, at = 100)
        val r = Merge.resolve(base, gone(500), side(note("x", rev = 1, at = 100)))!!
        assertNull(r.note)
    }

    @Test fun an_edit_after_the_delete_brings_the_note_back() {
        val base = note("x", rev = 1, at = 100)
        val r = Merge.resolve(base, gone(500), side(note("edited", rev = 2, at = 900)))!!
        assertEquals("edited", r.note!!.body)
    }

    @Test fun remote_delete_vs_local_edit_both_ways() {
        val base = note("x", rev = 1, at = 100)
        assertNull(Merge.resolve(base, side(note("x", rev = 1, at = 100)), gone(500))!!.note)
        assertEquals("edited", Merge.resolve(base, side(note("edited", rev = 2, at = 900)), gone(500))!!.note!!.body)
    }

    @Test fun missing_on_one_side_is_copied_over() {
        assertEquals("x", Merge.resolve(null, side(note("x")), null)!!.note!!.body)
        assertEquals("x", Merge.resolve(null, null, side(note("x")))!!.note!!.body)
    }

    // ---- the relay bug: a stale base turns a normal follow-up edit into a false conflict ----

    @Test fun follow_up_edit_after_adopting_theirs_is_not_a_conflict() {
        val v0 = note("hello", rev = 1)
        val fromOther = note("hello world", rev = 2)           // arrived from the other device
        val myEdit = note("hello world!", rev = 3)             // then I edited that same line
        // With the stale base (v0) both sides look changed on the same line: a false clash.
        assertNotNull(Merge.resolve(v0, side(myEdit), side(fromOther))!!.conflictCopy)
        // With the base updated to what I adopted, it is simply my edit.
        val r = Merge.resolve(fromOther, side(myEdit), side(fromOther))!!
        assertNull(r.conflictCopy)
        assertEquals("hello world!", r.note!!.body)
    }

    // ---- relay ordering ----

    @Test fun a_late_older_relay_is_ignored() {
        assertTrue(Merge.staleRelay(note("new", rev = 5), note("old", rev = 4)))
    }

    @Test fun same_or_newer_relay_is_not_ignored() {
        assertFalse(Merge.staleRelay(note("x", rev = 5), note("y", rev = 5)))
        assertFalse(Merge.staleRelay(note("x", rev = 5), note("y", rev = 6)))
        assertFalse(Merge.staleRelay(null, note("y", rev = 1)))
        assertFalse(Merge.staleRelay(note("x", rev = 5), null))          // a deletion is not "older"
    }

    // ---- categories ----

    @Test fun category_changed_on_one_side_is_kept_when_text_changed_on_the_other() {
        val base = note("a\nb")
        val ours = base.copy(category = "Work", rev = 2)
        val theirs = base.copy(body = "a\nB", rev = 2, updatedAt = 200)
        val r = Merge.resolve(base, side(ours), side(theirs))!!
        assertEquals("Work", r.note!!.category)
        assertEquals("a\nB", r.note!!.body)
        assertNull(r.conflictCopy)
    }

    @Test fun category_changed_only_on_the_other_side_wins() {
        val base = note("a", pinned = true).copy(category = "Home")
        val ours = base.copy(body = "a!", rev = 2)
        val theirs = base.copy(category = "Work", rev = 2, updatedAt = 200)
        assertEquals("Work", Merge.resolve(base, side(ours), side(theirs))!!.note!!.category)
    }

    @Test fun category_counts_as_content() {
        assertFalse(note("a").sameContent(note("a").copy(category = "Work")))
    }

    @Test fun category_names_are_cleaned_and_reuse_existing_spelling() {
        assertEquals("Work", Categories.clean("  \"Work\".\n"))
        assertEquals("Work", Categories.canonical("work", listOf("Home", "Work")))
        assertEquals("Travel", Categories.canonical("Travel", listOf("Home", "Work")))
        assertEquals(Categories.MAX, Categories.clean("x".repeat(80)).length)
    }
}
