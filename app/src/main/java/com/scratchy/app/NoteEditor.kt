package com.scratchy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.ViewOutlineProvider
import android.view.ContentInfo
import android.view.OnReceiveContentListener
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Title + multi-line body with autosave and markdown (lists, `- [ ]` checklists, headings, bold, code, links).
 * Used by both the floating panel and the full-screen note screen.
 */
class NoteEditor(
    ctx: Context,
    pal: Palette,
    note: Note,
    compact: Boolean,
    private val onOpenImage: ((File) -> Unit)? = null,
    private val onChanged: (Note) -> Unit,
) : LinearLayout(ctx) {

    var note: Note = note
        private set
    /** Called when a text field is touched (before it takes focus). Lets a non-focusable card become focusable. */
    var onTouched: ((View) -> Unit)? = null
    /** Called whenever a different note is loaded into the editor. */
    var onLoaded: ((Note) -> Unit)? = null
    /** An EditText that tells the editor when its selection changes (used for the card's own copy/paste menu). */
    private inner class Edit(ctx: Context) : EditText(ctx) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            selectionChanged(this)
        }

        /** Backspace at the start of a list item, quote or heading removes that format instead of joining lines. */
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val ic = super.onCreateInputConnection(outAttrs) ?: return null
            if (this !== bodyEdit) return ic
            return object : InputConnectionWrapper(ic, true) {
                override fun deleteSurroundingText(before: Int, after: Int) =
                    (before == 1 && after == 0 && dropFormat()) || super.deleteSurroundingText(before, after)
                override fun sendKeyEvent(ev: KeyEvent) =
                    (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DEL && dropFormat()) || super.sendKeyEvent(ev)
            }
        }

        /** Pasted text goes through the markdown reader, so an answer from an AI app arrives formatted. */
        override fun onTextContextMenuItem(id: Int): Boolean {
            if (this === bodyEdit && (id == android.R.id.paste || id == android.R.id.pasteAsPlainText)) {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                if (clip != null && clip.itemCount > 0 && !clip.description.hasMimeType("image/*")) {
                    val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                    if (text.isNotEmpty()) { pasteMarkdown(text); return true }
                }
            }
            return super.onTextContextMenuItem(id)
        }
    }

    private fun dropFormat(): Boolean {
        val c = bodyEdit.selectionStart
        if (c < 0 || c != bodyEdit.selectionEnd) return false
        val done = RichText.backspaceAtStart(bodyEdit.text, c)
        if (done) { scheduleSave(); refreshFormat(); bodyEdit.invalidate() }
        return done
    }

    private fun pasteMarkdown(text: String) {
        val a = bodyEdit.selectionStart.coerceAtLeast(0)
        val b = bodyEdit.selectionEnd.coerceAtLeast(0)
        RichText.insertMarkdown(bodyEdit.text, minOf(a, b), maxOf(a, b), text, palette, resources.displayMetrics.density, ::tableWidth)
    }

    private fun insertAtSelection(target: EditText, text: CharSequence) {
        val a = target.selectionStart.coerceAtLeast(0)
        val b = target.selectionEnd.coerceAtLeast(0)
        target.text.replace(minOf(a, b), maxOf(a, b), text)
    }
    val titleEdit: EditText = Edit(ctx)
    val bodyEdit: EditText = Edit(ctx)

    // The floating card is an overlay window, which cannot show Android's own Copy/Paste bar.
    // So the card gets its own small menu: Select all, Cut, Copy, Paste.
    private var ready = false
    private var menuTarget: EditText? = null
    private var menuByLongPress = false
    private val menuRow = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(2), dp(8), dp(2), dp(2))
    }
    private val menuCut by lazy { menuItem("Cut") { copySelection(cut = true) } }
    private val menuCopy by lazy { menuItem("Copy") { copySelection(cut = false) } }

    private val palette = pal
    private val images = note.images.toMutableList()
    private val imageBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val compactMode = compact
    private val suggestRow = LinearLayout(ctx)
    private val suggestScroll = HorizontalScrollView(ctx)
    private var suggesting = false
    /** The quiet category offer under the title. Asked at most once per note, and never for secret notes. */
    private val categoryOffer = android.widget.TextView(ctx)
    private val offered = HashSet<String>()
    private val askCategory = Runnable { offerCategory() }
    private val hideOffer = Runnable { categoryOffer.visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { commit() }
    private var suppress = false
    private var editing = false
    private var newlineAt = -1
    private var chStart = 0
    private var chBefore = 0
    private var chCount = 0
    /** The markdown the body had when it was last loaded or saved. Lets an untouched note keep its exact original text. */
    private var canon = ""
    private var panel: android.widget.PopupWindow? = null
    private val panelRefresh = ArrayList<() -> Unit>()
    private val linkRow = LinearLayout(ctx)
    private val linkField = EditText(ctx)

    init {
        orientation = VERTICAL
        titleEdit.apply {
            hint = "Title"
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setHorizontallyScrolling(false)
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 18f else 26f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = if (compact) 0f else -0.02f
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(4))
            bidi()
            setOnEditorActionListener { _, _, _ -> bodyEdit.requestFocus(); true }
        }
        bodyEdit.apply {
            hint = "Start typing…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 17f)
            setLineSpacing(0f, 1.15f)
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
            bidi()
        }
        // Title with the AI suggestion button at its end.
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleEdit, LayoutParams(0, -2, 1f))
            addView(ctx.iconButton(R.drawable.ic_sparkle, pal.accent, "Suggest a title") { suggestTitle() },
                LayoutParams(dp(38), dp(38)))
        }, LayoutParams(-1, -2))
        addView(View(ctx).apply { setBackgroundColor(pal.hairline) },
            LayoutParams(-1, 1).apply { marginStart = dp(4); marginEnd = dp(4) })
        addView(menuRow, LayoutParams(-1, -2))
        menuItem("Select all") { target().selectAll() }
        menuCut; menuCopy
        menuItem("Paste") { target().requestFocus(); pasteFromClipboard(); hideMenu() }
        suggestScroll.apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), dp(2))
            addView(suggestRow)
        }
        addView(suggestScroll, LayoutParams(-1, -2))
        categoryOffer.apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(pal.accent)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0); gravity = Gravity.CENTER
            background = roundRect((pal.accent and 0x00FFFFFF) or 0x26000000, dp(14).toFloat())
        }
        addView(categoryOffer, LayoutParams(-2, dp(28)).apply { topMargin = dp(6); marginStart = dp(4) })

        // One scrolling page: images sit in the flow of the note, above the text.
        bodyEdit.minHeight = dp(if (compact) 120 else 240)
        val page = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(imageBox, LayoutParams(-1, -2))
            addView(bodyEdit, LayoutParams(-1, -2, 1f))
        }
        addView(android.widget.ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }, LayoutParams(-1, 0, 1f))

        // The link field appears above the keyboard only while a link is being added (full note screen only).
        if (!compact) { buildLinkRow(ctx, pal); addView(linkRow, LayoutParams(-1, -2)) }

        load(note)

        // Images pasted from the keyboard's clipboard strip, long-press paste or drag and drop.
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = OnReceiveContentListener { _: View, payload: ContentInfo ->
                val clip = payload.clip
                if (clip.description.hasMimeType("image/*")) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { addImage(it) }
                    null
                } else payload   // text: fall through to normal paste
            }
            bodyEdit.setOnReceiveContentListener(arrayOf("image/*"), listener)
            titleEdit.setOnReceiveContentListener(arrayOf("image/*"), listener)
        }

        val schedule = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (!suppress) {
                    handler.removeCallbacks(autosave); handler.postDelayed(autosave, 500)
                    handler.removeCallbacks(askCategory); handler.postDelayed(askCategory, 3000)
                }
                if (compactMode && !suppress) hideMenu()
            }
        }
        titleEdit.addTextChangedListener(schedule)
        bodyEdit.addTextChangedListener(schedule)
        bodyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                chStart = start; chBefore = before; chCount = count
                newlineAt = if (!suppress && !editing && before == 0 && count == 1 && s?.get(start) == '\n') start else -1
            }
            override fun afterTextChanged(e: Editable?) {
                if (e == null || suppress || editing) return
                editing = true
                RichText.afterEdit(e, palette, resources.displayMetrics.density, newlineAt, chStart, chCount, chBefore == 0) {
                    bodyEdit.setSelection(it.coerceIn(0, e.length))
                }
                editing = false
                newlineAt = -1
                refreshFormat()
            }
        })

        // Tap a task's checkbox to toggle it.
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(ev: MotionEvent): Boolean {
                val layout = bodyEdit.layout ?: return false
                val text = bodyEdit.text
                val line = layout.getLineForVertical((ev.y + bodyEdit.scrollY - bodyEdit.totalPaddingTop).toInt())
                val off = layout.getLineStart(line)
                RichText.tableAt(text, off)?.let { (_, at) -> editTableAt(at); return true }
                val margin = RichText.taskMarginAt(text, off) ?: return false
                if (RichText.paraStart(text, off) == off && ev.x < bodyEdit.totalPaddingLeft + margin) {
                    if (RichText.toggleTaskAt(text, off)) { scheduleSave(); bodyEdit.invalidate() }
                }
                return false
            }
        })
        bodyEdit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { menuByLongPress = false; onTouched?.invoke(v) }
            detector.onTouchEvent(ev); false
        }
        titleEdit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { menuByLongPress = false; onTouched?.invoke(v) }
            false
        }
        if (compact) for (e in listOf(titleEdit, bodyEdit)) {
            e.setOnLongClickListener {
                menuByLongPress = true
                menuTarget = e
                showMenu()
                refreshMenu()
                false            // let Android still select the word
            }
        }
        ready = true
    }

    // ---- the card's own copy / paste menu ----

    private fun menuItem(label: String, onTap: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(palette.accent)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = roundRect(palette.hairline, dp(14).toFloat())
        pressable(0.95f) { onTap() }
    }.also { menuRow.addView(it, LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }

    private fun target(): EditText = menuTarget ?: if (titleEdit.hasFocus()) titleEdit else bodyEdit

    private fun showMenu() { menuRow.visibility = View.VISIBLE }

    private fun hideMenu() { menuByLongPress = false; menuRow.visibility = View.GONE }

    /** Cut and Copy only make sense with something selected; dim them otherwise. */
    private fun refreshMenu() {
        val t = target()
        val has = t.selectionStart != t.selectionEnd
        menuCut.alpha = if (has) 1f else 0.35f
        menuCopy.alpha = if (has) 1f else 0.35f
    }

    private fun selectionChanged(v: EditText) {
        if (ready && v === bodyEdit) refreshFormat()
        if (!ready || !compactMode) return
        menuTarget = v
        if (v.selectionStart != v.selectionEnd) showMenu()
        else if (!menuByLongPress) hideMenu()
        refreshMenu()
    }

    private fun copySelection(cut: Boolean) {
        val t = target()
        val a = minOf(t.selectionStart, t.selectionEnd)
        val b = maxOf(t.selectionStart, t.selectionEnd)
        if (a < 0 || a == b) return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Scratchy", t.text.subSequence(a, b)))
        if (cut) t.text.delete(a, b)
        Toast.makeText(context, if (cut) "Cut" else "Copied", Toast.LENGTH_SHORT).show()
        hideMenu()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** The checklist button in the note screen and the card: makes the selected lines tasks, or plain again. */
    fun toggleChecklist() {
        bodyEdit.requestFocus()
        val (a, b) = selection()
        RichText.setList(bodyEdit.text, a, b, Markdown.Kind.TASK, palette, resources.displayMetrics.density)
        afterFormat()
    }

    /**
     * A sync brought in a newer copy of the note being edited. Show it, unless there are unsaved edits
     * here (then those win and get saved in a moment).
     */
    fun refreshFrom(n: Note) {
        if (n.id != note.id) return
        if (titleEdit.text.toString() != note.title || RichText.toMarkdown(bodyEdit.text) != canon) return   // unsaved typing wins
        if (n.sameContent(note)) { note = n; return }                      // nothing to show, just catch up the counters
        if (n.rev < note.rev || (n.rev == note.rev && n.updatedAt <= note.updatedAt)) return
        val sel = bodyEdit.selectionStart.coerceAtLeast(0)
        load(n)
        bodyEdit.setSelection(minOf(sel, bodyEdit.text.length))
    }

    fun load(n: Note) {
        suppress = true
        handler.removeCallbacks(askCategory); handler.removeCallbacks(hideOffer)
        categoryOffer.visibility = View.GONE
        note = n
        images.clear(); images.addAll(n.images)
        titleEdit.setText(n.title)
        bodyEdit.setText(RichText.fromMarkdown(n.body, palette, resources.displayMetrics.density, ::tableWidth), TextView.BufferType.EDITABLE)
        canon = RichText.toMarkdown(bodyEdit.text)
        bodyEdit.setSelection(bodyEdit.text.length)
        suppress = false
        renderImages()
        onLoaded?.invoke(n)
    }

    /** Copies the image into the app, shows it as a thumbnail and saves the note. */
    fun addImage(uri: Uri) {
        Attachments.import(context, uri) { name ->
            if (name == null) {
                Toast.makeText(context, "Couldn't add that image", Toast.LENGTH_SHORT).show()
            } else {
                images.add(name)
                renderImages()
                commit()
            }
        }
    }

    /** Pastes clipboard text at the cursor, or attaches clipboard images. */
    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return
        }
        if (clip.description.hasMimeType("image/*")) {
            var any = false
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { addImage(it); any = true }
            if (any) return
        }
        val text = clip.getItemAt(0).coerceToText(context)
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "Nothing to paste", Toast.LENGTH_SHORT).show(); return
        }
        val target = if (titleEdit.hasFocus()) titleEdit else bodyEdit
        target.requestFocus()
        if (target === bodyEdit) pasteMarkdown(text.toString()) else insertAtSelection(target, text.toString().replace("\r\n", "\n"))
    }

    // ---- formatting bar ----

    private fun selection(): Pair<Int, Int> {
        val a = bodyEdit.selectionStart.coerceAtLeast(0)
        val b = bodyEdit.selectionEnd.coerceAtLeast(0)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun scheduleSave() { handler.removeCallbacks(autosave); handler.postDelayed(autosave, 500) }

    private fun afterFormat() { scheduleSave(); refreshFormat(); bodyEdit.invalidate() }

    private fun buildLinkRow(ctx: Context, pal: Palette) {
        linkRow.apply {
            visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundRect(pal.surface, 0f)
            linkField.apply {
                hint = "Paste a link"; setSingleLine(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(pal.text); setHintTextColor(pal.secondary)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = roundRect(pal.track, dp(14).toFloat())
            }
            addView(linkField, LayoutParams(0, -2, 1f))
            addView(TextView(ctx).apply {
                text = "Add"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(pal.onAccent); setPadding(dp(18), 0, dp(18), 0)
                background = roundRect(pal.accent, dp(20).toFloat())
                pressable(0.95f) { applyLink() }
            }, LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
        }
    }

    // ---- the Format panel (opened from the note screen's top bar) ----

    private fun tableWidth() = (bodyEdit.measuredWidth - bodyEdit.paddingLeft - bodyEdit.paddingRight).takeIf { it > 0 }
        ?: (resources.displayMetrics.widthPixels - dp(40))

    /** A panel with large, labelled tiles. It does not take focus, so the keyboard and your selection stay as they are. */
    fun showFormatPanel(anchor: View) {
        panel?.dismiss()
        val density = resources.displayMetrics.density
        panelRefresh.clear()
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundRect(palette.surface, dp(22).toFloat(), (palette.text and 0xFFFFFF) or 0x24000000, dp(1))
            elevation = dp(10).toFloat()
        }
        fun section(name: String) = root.addView(TextView(context).apply {
            text = name.uppercase(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); letterSpacing = 0.1f
            setTextColor(palette.secondary); setPadding(dp(4), dp(10), 0, dp(4))
        })
        fun row() = LinearLayout(context).also { root.addView(it, LayoutParams(-1, -2)) }
        fun tile(into: LinearLayout, kind: FormatKind, label: String, iconLabel: String = "", active: () -> Boolean, keepOpen: Boolean = false, tap: () -> Unit) {
            val icon = FormatIcon(context, kind, palette).also { it.label = iconLabel }
            val name = TextView(context).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.CENTER; maxLines = 1 }
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(icon, LayoutParams(dp(52), dp(40)))
                addView(name, LayoutParams(-2, -2))
                setPadding(0, dp(2), 0, dp(4))
                pressable(0.94f) {
                    bodyEdit.requestFocus(); tap()
                    if (keepOpen) panelRefresh.forEach { it() } else panel?.dismiss()
                }
            }
            val refresh = { val on = active(); icon.active = on; name.setTextColor(if (on) palette.accent else palette.secondary) }
            panelRefresh += refresh; refresh()
            into.addView(cell, LayoutParams(0, -2, 1f))
        }
        val t = { bodyEdit.text }
        fun sel() = selection()
        fun inline(k: Inline) { val (a, b) = sel(); RichText.toggleInline(t(), k, a, b, palette); afterFormat() }
        fun isIn(k: Inline) = sel().let { (a, b) -> RichText.isActive(t(), k, a, b) }
        fun kind() = RichText.kindAt(t(), sel().first)
        fun heading(level: Int) { val (a, b) = sel(); RichText.setHeading(t(), a, b, level); afterFormat() }
        fun list(k: Markdown.Kind) { val (a, b) = sel(); RichText.setList(t(), a, b, k, palette, density); afterFormat() }

        section("Text")
        row().apply {
            tile(this, FormatKind.BOLD, "Bold", active = { isIn(Inline.BOLD) }, keepOpen = true) { inline(Inline.BOLD) }
            tile(this, FormatKind.ITALIC, "Italic", active = { isIn(Inline.ITALIC) }, keepOpen = true) { inline(Inline.ITALIC) }
            tile(this, FormatKind.STRIKE, "Strike", active = { isIn(Inline.STRIKE) }, keepOpen = true) { inline(Inline.STRIKE) }
            tile(this, FormatKind.CODE, "Code", active = { isIn(Inline.CODE) }, keepOpen = true) { inline(Inline.CODE) }
            tile(this, FormatKind.LINK, "Link", active = { sel().let { (a, b) -> RichText.linkAt(t(), a, b) != null } }) { showLinkRow() }
        }
        section("Heading")
        row().apply {
            for (l in 1..3) tile(this, FormatKind.HEADING, listOf("Large", "Medium", "Small")[l - 1], "H$l",
                active = { (kind() as? RHeading)?.level == l }) { heading(l) }
            tile(this, FormatKind.HEADING, "Normal", "Aa", active = { false }) {
                val (a, b) = sel(); (kind() as? RHeading)?.let { RichText.setHeading(t(), a, b, it.level) }; afterFormat()
            }
        }
        section("Lists")
        row().apply {
            tile(this, FormatKind.BULLET, "Bullets", active = { (kind() as? RList)?.kind == Markdown.Kind.BULLET }) { list(Markdown.Kind.BULLET) }
            tile(this, FormatKind.NUMBER, "Numbers", active = { (kind() as? RList)?.kind == Markdown.Kind.NUMBER }) { list(Markdown.Kind.NUMBER) }
            tile(this, FormatKind.TASK, "Checklist", active = { (kind() as? RList)?.kind == Markdown.Kind.TASK }) { list(Markdown.Kind.TASK) }
            tile(this, FormatKind.QUOTE, "Quote", active = { kind() is RQuote }) { val (a, b) = sel(); RichText.setQuote(t(), a, b, palette, density); afterFormat() }
        }
        section("Insert")
        row().apply {
            tile(this, FormatKind.TABLE, "Table", active = { false }) { insertTable() }
            tile(this, FormatKind.OUTDENT, "Outdent", active = { false }, keepOpen = true) { val (a, b) = sel(); RichText.shift(t(), a, b, -1); afterFormat() }
            tile(this, FormatKind.INDENT, "Indent", active = { false }, keepOpen = true) { val (a, b) = sel(); RichText.shift(t(), a, b, 1); afterFormat() }
            addView(View(context), LayoutParams(0, 1, 2f))
        }

        val w = minOf(resources.displayMetrics.widthPixels - dp(24), dp(400))
        panel = android.widget.PopupWindow(root, w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            animationStyle = android.R.style.Animation_Dialog
            showAsDropDown(anchor, 0, dp(6), Gravity.END)
        }
    }

    private fun insertTable() {
        val (a, _) = selection()
        val at = RichText.insertTable(bodyEdit.text, a, TableData.blank(), palette, resources.displayMetrics.density, ::tableWidth)
        afterFormat()
        editTableAt(at)
    }

    /** Opens the table editor for the table at [pos] (the card has no activity to show it in). */
    private fun editTableAt(pos: Int) {
        val (span, at) = RichText.tableAt(bodyEdit.text, pos) ?: return
        val act = context as? android.app.Activity
        if (act == null) { Toast.makeText(context, "Open the note in the app to edit its table", Toast.LENGTH_SHORT).show(); return }
        TableEditor.show(act, palette, span.data) { result ->
            val t = bodyEdit.text
            val cur = RichText.tableAt(t, at)?.first ?: return@show
            if (result == null) {
                var end = at + 1
                if (end < t.length && t[end] == '\n') end++
                t.delete(at, end)
            } else RichText.replaceTable(t, at, cur, result, palette, resources.displayMetrics.density, ::tableWidth)
            afterFormat()
        }
    }

    private var linkRange = 0 to 0

    private fun showLinkRow() {
        linkRange = selection()
        linkField.setText(RichText.linkAt(bodyEdit.text, linkRange.first, linkRange.second).orEmpty())
        linkRow.visibility = View.VISIBLE
        linkField.requestFocus()
    }

    private fun applyLink() {
        val (a, b) = linkRange
        val url = linkField.text.toString().trim().let { if (it.isNotEmpty() && !it.contains("://") && !it.startsWith("mailto:")) "https://$it" else it }
        RichText.setLink(bodyEdit.text, a.coerceAtMost(bodyEdit.text.length), b.coerceAtMost(bodyEdit.text.length), url, palette)
        linkRow.visibility = View.GONE
        linkField.setText("")
        bodyEdit.requestFocus()
        afterFormat()
    }

    /** The open Format panel lights up for whatever the cursor or selection is in. */
    private fun refreshFormat() { if (panel?.isShowing == true) panelRefresh.forEach { it() } }

    /** Asks the AI for three titles from the text and/or first image, shown as tappable chips. */
    fun suggestTitle() {
        if (suggesting) return
        if (!Ai.configured(context)) {
            Toast.makeText(context, "Add your OpenRouter key in Settings first", Toast.LENGTH_LONG).show()
            context.startActivity(
                android.content.Intent(context, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (note.secret) {
            Toast.makeText(context, "Titles by AI are off for secret notes", Toast.LENGTH_SHORT).show()
            return
        }
        val body = bodyEdit.text.toString().trim()
        val image = images.firstOrNull()?.let { Attachments.file(context, it) }
        if (body.isBlank() && image == null) {
            Toast.makeText(context, "Write something or add an image first", Toast.LENGTH_SHORT).show()
            return
        }
        suggesting = true
        showChips(listOf("Thinking…"), tappable = false)
        Ai.suggestTitles(context, body, image) { titles, error ->
            suggesting = false
            if (titles != null) showChips(titles, tappable = true)
            else showChips(listOf(error ?: "Couldn't get suggestions"), tappable = false)
        }
    }

    /** After a pause in typing: ask the AI once for a category and show it as a small tappable chip. */
    private fun offerCategory() {
        val n = note
        val text = (titleEdit.text.toString() + "\n" + bodyEdit.text.toString()).trim()
        if (!AiSettings.suggestCategories(context) || !Ai.configured(context)) return
        if (n.secret || n.category.isNotBlank() || n.id in offered || text.length < 25) return
        offered += n.id
        Categories.suggestFor(context, n.copy(title = titleEdit.text.toString(), body = bodyEdit.text.toString())) { name, _ ->
            if (name == null || note.id != n.id || note.category.isNotBlank() || note.secret) return@suggestFor
            categoryOffer.text = "✦ $name"
            categoryOffer.visibility = View.VISIBLE
            categoryOffer.setOnClickListener {
                handler.removeCallbacks(hideOffer); categoryOffer.visibility = View.GONE
                setCategory(name)
                onCategoryChanged?.invoke(note)
            }
            handler.removeCallbacks(hideOffer); handler.postDelayed(hideOffer, 15000)
        }
    }

    /** Called after a category was set by accepting the offered one (so a screen can refresh its own label). */
    var onCategoryChanged: ((Note) -> Unit)? = null

    private fun showChips(items: List<String>, tappable: Boolean) {
        suggestRow.removeAllViews()
        suggestScroll.visibility = View.VISIBLE
        for (t in items) {
            suggestRow.addView(android.widget.TextView(context).apply {
                text = t
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (tappable) palette.accent else palette.secondary)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = roundRect(
                    if (tappable) (palette.accent and 0x00FFFFFF) or 0x26000000 else palette.hairline,
                    dp(18).toFloat()
                )
                maxLines = 2
                maxWidth = dp(300)
                ellipsize = android.text.TextUtils.TruncateAt.END
                bidi()
                if (tappable) pressable(0.96f) {
                    titleEdit.setText(t)
                    titleEdit.setSelection(titleEdit.text.length)
                    suggestScroll.visibility = View.GONE
                }
            }, LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    /** Shows each image as a full-width, rounded block in the note's flow. */
    private fun renderImages() {
        imageBox.removeAllViews()
        imageBox.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
        val outline = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
        }
        val screenW = resources.displayMetrics.widthPixels
        for (name in images.toList()) {
            val cell = FrameLayout(context)
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(palette.hairline)
                outlineProvider = outline
                clipToOutline = true
                contentDescription = "Image in note"
                setOnClickListener { onOpenImage?.invoke(Attachments.file(context, name)) }
            }
            cell.addView(iv, FrameLayout.LayoutParams(dp(110), dp(110)))
            Attachments.thumb(context, name, screenW) { bmp ->
                if (bmp == null) return@thumb
                imageBox.post {
                    // Keep the image's proportions, shrunk to fit a modest box (no cropping).
                    val availW = (imageBox.width - dp(8)).coerceAtLeast(dp(120))
                    val maxW = minOf(availW, dp(if (compactMode) 200 else 280))
                    val maxH = dp(if (compactMode) 140 else 220)
                    val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
                    iv.layoutParams = FrameLayout.LayoutParams(
                        (bmp.width * scale).toInt(), (bmp.height * scale).toInt()
                    )
                    iv.scaleType = ImageView.ScaleType.FIT_XY
                    iv.setImageBitmap(bmp)
                }
            }
            val remove = ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(0xFFFFFFFF.toInt())
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = roundRect(0x99000000.toInt(), dp(12).toFloat())
                contentDescription = "Remove image"
                setOnClickListener {
                    images.remove(name)
                    Attachments.delete(context, name)
                    renderImages()
                    commit()
                }
            }
            cell.addView(remove, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.RIGHT)
                .apply { setMargins(0, dp(6), dp(6), 0) })
            imageBox.addView(cell, LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(6); marginStart = dp(4) })
        }
    }

    fun setPinned(pinned: Boolean) {
        commit()
        note = note.copy(pinned = pinned, images = images.toList())
        onChanged(note)
    }

    fun setCategory(name: String) {
        categoryOffer.visibility = View.GONE
        commit()
        note = note.copy(category = name, images = images.toList())
        onChanged(note)
    }

    fun setSecret(secret: Boolean) {
        commit()
        note = note.copy(secret = secret, category = "", images = images.toList())
        onChanged(note)
    }

    /** Writes pending edits now (call before leaving). */
    fun commit() {
        handler.removeCallbacks(autosave)
        val t = titleEdit.text.toString()
        val md = RichText.toMarkdown(bodyEdit.text)
        val bodyChanged = md != canon
        if (t == note.title && !bodyChanged && images == note.images) return
        canon = md
        note = note.copy(title = t, body = if (bodyChanged) md else note.body, images = images.toList(), updatedAt = System.currentTimeMillis())
        onChanged(note)
    }

}
