package com.scratchy.app

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

/** Full-screen editor for one note. Autosaves; a new note that stays empty is never stored. */
class NoteActivity : Activity() {

    private lateinit var editor: NoteEditor
    private lateinit var pinButton: ImageView
    private var it0: View? = null
    private lateinit var pal: Palette
    private var refreshFromStore: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Palette(this)
        setTheme(
            if (pal.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = pal.background
        window.navigationBarColor = pal.background
        if (!pal.dark) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val existing = intent.getStringExtra(EXTRA_ID)?.let { NoteStore.get(this, it) }
        val wantSecret = existing?.secret ?: intent.getBooleanExtra(MainActivity.EXTRA_SECRET, false)
        if (wantSecret && !Vault.isOpen()) { finish(); return }     // never show a secret note while locked
        if (wantSecret) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val note = existing ?: NoteStore.newNote().copy(secret = wantSecret)
        editor = NoteEditor(this, pal, note, compact = false, onOpenImage = { showImage(it) }) {
            NoteStore.save(this, it)
        }
        editor.setPadding(dp(16), 0, dp(16), 0)

        fun tint(pinned: Boolean) = pinButton.setColorFilter(if (pinned) pal.accent else pal.secondary)
        pinButton = iconButton(R.drawable.ic_pin, pal.secondary, "Pin") {
            editor.setPinned(!editor.note.pinned); tint(editor.note.pinned)
        }
        tint(note.pinned)

        var madeSecretHere = wantSecret && existing == null
        val lockButton = iconButton(R.drawable.ic_lock, if (note.secret) pal.accent else pal.secondary, "Secret note") {
            val on = !editor.note.secret
            if (on && !Vault.isSetUp(this)) {
                Toast.makeText(this, "Set up secret notes first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECRET, true))
            } else if (!on && !madeSecretHere && !Vault.isOpen()) {
                Toast.makeText(this, "Unlock secret notes first", Toast.LENGTH_SHORT).show()
            } else {
                if (on) madeSecretHere = true
                editor.setSecret(on)
                (it0 as ImageView).setColorFilter(if (on) pal.accent else pal.secondary)
                showCategory()
                Toast.makeText(this, if (on) "Secret. Find it under the lock." else "No longer secret", Toast.LENGTH_SHORT).show()
            }
        }
        it0 = lockButton

        // A sync brought in a newer copy of this note while it is open: show it (unless there are unsaved edits here).
        refreshFromStore = {
            NoteStore.get(this, editor.note.id)?.let { n ->
                editor.refreshFrom(n)
                tint(editor.note.pinned)
                (it0 as? ImageView)?.setColorFilter(if (editor.note.secret) pal.accent else pal.secondary)
                showCategory()
            }
        }

        val top = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(iconButton(R.drawable.ic_back, pal.text, "Back") { finish() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(View(this@NoteActivity), LinearLayout.LayoutParams(0, 1, 1f))
            val aiButton = FormatIcon(this@NoteActivity, FormatKind.AI, pal).apply {
                tint = pal.accent; contentDescription = "Format with AI"
                pressable(0.9f) { editor.formatWithAi() }
            }
            addView(aiButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            val formatButton = iconButton(R.drawable.ic_format, pal.accent, "Format") { formatAnchor?.let { editor.showFormatPanel(it) } }
            formatAnchor = formatButton
            addView(formatButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_paste, pal.secondary, "Paste") { editor.pasteFromClipboard() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_screenshot, pal.secondary, "Attach latest screenshot") {
                if (!Screenshots.attachLatest(this@NoteActivity, editor)) {
                    Toast.makeText(this@NoteActivity, "Allow photo access in the notes list first", Toast.LENGTH_LONG).show()
                }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(lockButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(pinButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_delete, pal.secondary, "Delete") { confirmDelete() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        editor.onCategoryChanged = { showCategory() }
        categoryPill = TextView(this).apply {
            textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(12), 0, dp(12), 0); gravity = android.view.Gravity.CENTER
            pressable(0.95f) {
                if (editor.note.secret) Toast.makeText(this@NoteActivity, "Secret notes have no category", Toast.LENGTH_SHORT).show()
                else Categories.pick(this@NoteActivity, editor.note.category, editor.note) { name ->
                    editor.setCategory(name); showCategory()
                }
            }
        }
        showCategory()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.background)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(categoryPill, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(20); bottomMargin = dp(4) })
            addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        if (existing == null) editor.titleEdit.requestFocus()
    }

    private lateinit var categoryPill: TextView
    private var formatAnchor: View? = null

    private fun showCategory() {
        val c = editor.note.category
        val secret = editor.note.secret
        categoryPill.visibility = if (secret) android.view.View.GONE else android.view.View.VISIBLE
        categoryPill.text = if (c.isEmpty()) "＋ Category" else c
        categoryPill.setTextColor(if (c.isEmpty()) pal.secondary else pal.accent)
        categoryPill.background = roundRect(
            if (c.isEmpty()) pal.surface else (pal.accent and 0x00FFFFFF) or 0x26000000, dp(15).toFloat())
    }

    /** Full-screen look at an attached image; tap anywhere to close. */
    private fun showImage(file: java.io.File) {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return
        Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(android.widget.ImageView(this@NoteActivity).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
                contentDescription = "Attached image. Tap to close."
                setOnClickListener { dismiss() }
            })
            show()
        }
    }

    private fun confirmDelete() {
        Sheet(this, pal).title("Delete this note?").message("This can't be undone.")
            .confirm("Delete", danger = true) {
                NoteStore.delete(this, editor.note.id)
                finish()
            }.show()
    }

    override fun onPause() {
        if (Sync.onChangedNote === refreshFromStore) Sync.onChangedNote = null
        Live.release("note")
        if (::editor.isInitialized) editor.commit()
        super.onPause()
    }

    override fun onStop() {
        if (::editor.isInitialized && editor.note.secret) Vault.left()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Palette(this).signature != pal.signature) { recreate(); return }   // the look changed (color setting, wallpaper)
        Sync.onChangedNote = refreshFromStore
        Live.acquire(this, "note")            // keep the instant connection open while a note is on screen
        if (::editor.isInitialized && editor.note.secret) Vault.returned()
    }

    companion object {
        const val EXTRA_ID = "note_id"
    }
}
