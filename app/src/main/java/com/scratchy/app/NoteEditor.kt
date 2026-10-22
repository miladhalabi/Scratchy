package com.scratchy.app

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
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.ViewOutlineProvider
import android.view.ContentInfo
import android.view.OnReceiveContentListener
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/**
 * Title + multi-line body with autosave and simple checklists (lines starting with ☐ / ☑).
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
    val titleEdit = EditText(ctx)
    val bodyEdit = EditText(ctx)

    private val palette = pal
    private val images = note.images.toMutableList()
    private val imageBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val compactMode = compact
    private val suggestRow = LinearLayout(ctx)
    private val suggestScroll = HorizontalScrollView(ctx)
    private var suggesting = false
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { commit() }
    private var suppress = false
    private var editing = false
    private var newlineAt = -1

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
        suggestScroll.apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), dp(2))
            addView(suggestRow)
        }
        addView(suggestScroll, LayoutParams(-1, -2))

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
                if (!suppress) { handler.removeCallbacks(autosave); handler.postDelayed(autosave, 500) }
            }
        }
        titleEdit.addTextChangedListener(schedule)
        bodyEdit.addTextChangedListener(schedule)
        bodyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                newlineAt = if (!suppress && !editing && before == 0 && count == 1 && s?.get(start) == '\n') start else -1
            }
            override fun afterTextChanged(e: Editable?) {
                e ?: return
                continueChecklist(e)
                Checklist.style(e, palette, resources.displayMetrics.density)
            }
        })

        // Tap a ☐ / ☑ to toggle it.
        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(ev: MotionEvent): Boolean {
                val text = bodyEdit.text
                val off = bodyEdit.getOffsetForPosition(ev.x, ev.y)
                val lineStart = text.lastIndexOf('\n', off - 1) + 1
                if (off <= lineStart + 1 && lineStart < text.length) {
                    val c = text[lineStart]
                    if (c == BOX_OFF || c == BOX_ON) {
                        editing = true
                        text.replace(lineStart, lineStart + 1, (if (c == BOX_OFF) BOX_ON else BOX_OFF).toString())
                        editing = false
                    }
                }
                return false
            }
        })
        bodyEdit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke(v)
            detector.onTouchEvent(ev); false
        }
        titleEdit.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke(v)
            false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Enter on a checklist line starts a new item; Enter on an empty item ends the list. */
    private fun continueChecklist(e: Editable) {
        val nl = newlineAt
        newlineAt = -1
        if (nl < 0 || editing) return
        val prevStart = e.lastIndexOf('\n', nl - 1) + 1
        val prev = e.substring(prevStart, nl)
        if (!prev.startsWith("$BOX_OFF ") && !prev.startsWith("$BOX_ON ")) return
        editing = true
        if (prev.length == 2) {
            e.delete(prevStart, nl + 1)
            bodyEdit.setSelection(prevStart)
        } else {
            e.insert(nl + 1, "$BOX_OFF ")
            bodyEdit.setSelection(nl + 3)
        }
        editing = false
    }

    /** Adds or removes the checkbox prefix on the line at the cursor. */
    fun toggleChecklist() {
        bodyEdit.requestFocus()
        val text = bodyEdit.text
        val sel = bodyEdit.selectionStart.coerceAtLeast(0)
        val lineStart = text.lastIndexOf('\n', sel - 1) + 1
        editing = true
        if (text.startsWith("$BOX_OFF ", lineStart) || text.startsWith("$BOX_ON ", lineStart)) {
            text.delete(lineStart, lineStart + 2)
        } else {
            text.insert(lineStart, "$BOX_OFF ")
        }
        editing = false
    }

    fun load(n: Note) {
        suppress = true
        note = n
        images.clear(); images.addAll(n.images)
        titleEdit.setText(n.title)
        bodyEdit.setText(n.body)
        bodyEdit.setSelection(bodyEdit.text.length)
        suppress = false
        renderImages()
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
        val a = target.selectionStart.coerceAtLeast(0)
        val b = target.selectionEnd.coerceAtLeast(0)
        target.text.replace(minOf(a, b), maxOf(a, b), text)
    }

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

    /** Writes pending edits now (call before leaving). */
    fun commit() {
        handler.removeCallbacks(autosave)
        val t = titleEdit.text.toString()
        val b = bodyEdit.text.toString()
        if (t == note.title && b == note.body && images == note.images) return
        note = note.copy(title = t, body = b, images = images.toList(), updatedAt = System.currentTimeMillis())
        onChanged(note)
    }

    companion object {
        const val BOX_OFF = '☐'
        const val BOX_ON = '☑'
    }
}
