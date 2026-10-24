package com.scratchy.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
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
        val input = EditText(a).apply {
            setText(initial); setSelection(text.length); hint = "Category name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX))
        }
        val box = android.widget.FrameLayout(a).apply { setPadding(a.dp(22), a.dp(8), a.dp(22), 0); addView(input) }
        AlertDialog.Builder(a).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> clean(input.text.toString()).takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Pick, create, suggest with AI, or clear the category of one note. [apply] gets the chosen name ("" = none). */
    fun pick(a: Activity, current: String, note: Note, apply: (String) -> Unit) {
        val existing = names(a)
        val labels = ArrayList<String>(); val actions = ArrayList<() -> Unit>()
        existing.forEach { n -> labels += if (n == current) "✓  $n" else n; actions += { apply(n) } }
        labels += "＋  New category…"; actions += {
            ask(a, "New category", "", "Add") { apply(canonical(it, existing)) }
        }
        labels += "✦  Suggest with AI"; actions += {
            if (!Ai.configured(a)) Toast.makeText(a, "Add your OpenRouter key in Settings first", Toast.LENGTH_LONG).show()
            else {
                Toast.makeText(a, "Thinking…", Toast.LENGTH_SHORT).show()
                suggestFor(a, note) { name, err ->
                    if (name != null) { apply(name); Toast.makeText(a, "Filed under $name", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(a, err ?: "No suggestion", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (current.isNotEmpty()) { labels += "No category"; actions += { apply("") } }
        AlertDialog.Builder(a).setTitle("Category").setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Rename or remove a category (long press on its chip). */
    fun manage(a: Activity, name: String, changed: () -> Unit) {
        AlertDialog.Builder(a).setTitle(name).setItems(arrayOf("Rename", "Remove category")) { _, i ->
            if (i == 0) ask(a, "Rename category", name, "Rename") { rename(a, name, it); changed() }
            else AlertDialog.Builder(a).setTitle("Remove \"$name\"?")
                .setMessage("The notes stay, they just lose this category.")
                .setPositiveButton("Remove") { _, _ -> remove(a, name); changed() }
                .setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Cancel", null).show()
    }
}
