package com.scratchy.app

import android.app.Activity
import android.content.Context
import android.widget.Toast

/**
 * Categories are just a name on each note, so there is no separate list to keep in sync: the list of
 * categories is whatever names the (non-secret) notes use, and one with no notes left disappears.
 */
object Categories {
    const val MAX = 30

    /** Trims, drops line breaks and quotes, caps the length. */
    fun clean(raw: String) = raw.replace('\n', ' ').trim().trim('"', '\'', '.', ',', ' ').take(MAX).trim()

    /** The spelling already in use when [raw] only differs in case, so "work" never becomes a second "Work". */
    fun canonical(raw: String, existing: Collection<String>): String {
        val c = clean(raw)
        return existing.firstOrNull { it.equals(c, ignoreCase = true) } ?: c
    }

    /** Names in use, most used first. */
    fun names(ctx: Context): List<String> =
        NoteStore.list(ctx).filter { it.category.isNotBlank() }.groupingBy { it.category }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }

    fun set(ctx: Context, id: String, name: String) {
        val n = NoteStore.get(ctx, id) ?: return
        if (!n.secret && n.category != name) NoteStore.save(ctx, n.copy(category = name))
    }

    fun rename(ctx: Context, from: String, to: String) {
        val target = canonical(to, names(ctx).filter { it != from })
        if (target.isEmpty()) return
        NoteStore.list(ctx).filter { it.category == from }.forEach { NoteStore.save(ctx, it.copy(category = target)) }
    }

    /** Takes the category off its notes; the notes stay. */
    fun remove(ctx: Context, name: String) =
        NoteStore.list(ctx).filter { it.category == name }.forEach { NoteStore.save(ctx, it.copy(category = "")) }

    private fun text(n: Note) = (n.title.trim() + "\n" + n.preview).trim()

    /**
     * Files every uncategorized note (that has text) with the AI, in batches. A note that fits no existing
     * category gets a new one. [done] runs on the main thread with the number of notes filed and an error, if any.
     */
    fun sortWithAi(ctx: Context, done: (filed: Int, error: String?) -> Unit) {
        val todo = NoteStore.list(ctx).filter { it.category.isBlank() && text(it).isNotBlank() }
        if (todo.isEmpty()) { done(0, null); return }
        val app = ctx.applicationContext
        var filed = 0
        fun batch(from: Int) {
            if (from >= todo.size) { done(filed, null); return }
            val part = todo.subList(from, minOf(todo.size, from + 30))
            Ai.categorize(app, part.map(::text), names(app)) { map, err ->
                if (map == null) { done(filed, err); return@categorize }
                part.forEachIndexed { i, n ->
                    val name = map[i]?.let { canonical(it, names(app)) }.orEmpty()
                    val cur = NoteStore.get(app, n.id)
                    if (name.isNotEmpty() && cur != null && cur.category.isBlank()) { set(app, n.id, name); filed++ }
                }
                batch(from + part.size)
            }
        }
        batch(0)
    }

    /** One note: asks the AI, then files it. */
    fun suggestFor(ctx: Context, note: Note, done: (String?, String?) -> Unit) {
        val t = text(note)
        if (t.isBlank()) { done(null, "Write something first"); return }
        Ai.categorize(ctx, listOf(t), names(ctx).filter { it.isNotBlank() }) { map, err ->
            val name = map?.get(0)?.let { canonical(it, names(ctx)) }.orEmpty()
            if (name.isEmpty()) done(null, err ?: "No suggestion") else done(name, null)
        }
    }

    // ---- dialogs ----

    private fun ask(a: Activity, title: String, initial: String, ok: String, onOk: (String) -> Unit) {
        Sheet(a).title(title).field("Category name", initial, maxLength = MAX)
            .confirm(ok) { v -> clean(v[0]).takeIf { it.isNotEmpty() }?.let(onOk) }.show()
    }

    /** Pick, create, suggest with AI, or clear the category of one note. [apply] gets the chosen name ("" = none). */
    fun pick(a: Activity, current: String, note: Note, apply: (String) -> Unit) {
        val existing = names(a)
        val s = Sheet(a).title("Category")
        existing.forEach { n -> s.item(n, checked = n == current) { apply(n) } }
        s.item("＋  New category", accent = true) {
            ask(a, "New category", "", "Add") { apply(canonical(it, existing)) }
        }
        s.item("✦  Suggest with AI", accent = true) {
            if (!Ai.configured(a)) Toast.makeText(a, "Add your OpenRouter key in Settings first", Toast.LENGTH_LONG).show()
            else {
                Toast.makeText(a, "Thinking…", Toast.LENGTH_SHORT).show()
                suggestFor(a, note) { name, err ->
                    if (name != null) { apply(name); Toast.makeText(a, "Filed under $name", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(a, err ?: "No suggestion", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (current.isNotEmpty()) s.item("No category") { apply("") }
        s.show()
    }

    /** Rename or remove a category (long press on its chip). */
    fun manage(a: Activity, name: String, changed: () -> Unit) {
        Sheet(a).title(name)
            .item("Rename") { ask(a, "Rename category", name, "Rename") { rename(a, name, it); changed() } }
            .item("Remove category") {
                Sheet(a).title("Remove \"$name\"?").message("The notes stay, they just lose this category.")
                    .confirm("Remove", danger = true) { remove(a, name); changed() }.show()
            }.show()
    }
}
