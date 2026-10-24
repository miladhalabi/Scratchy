package com.scratchy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.content.pm.PackageManager
import androidx.dynamicanimation.animation.DynamicAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home: overlay on/off, search, and the list of notes (pinned first). */
class MainActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var toggle: TextView
    private lateinit var permission: TextView
    private lateinit var photoAccess: TextView
    private var photoDenied = false
    private lateinit var search: EditText
    private lateinit var notes: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var chipScroll: android.widget.HorizontalScrollView
    /** Category filter: null = all notes, "" = notes without a category. */
    private var filter: String? = null
    private var sorting = false
    private lateinit var root: FrameLayout
    private lateinit var syncLine: TextView
    /** True when this screen is the vault: only secret notes, behind the password / fingerprint lock. */
    private val secretMode by lazy { intent.getBooleanExtra(EXTRA_SECRET, false) }
    private var gate: VaultGate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Palette(this)
        setTheme(
            if (pal.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        if (secretMode) window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = pal.background
        window.navigationBarColor = pal.background
        if (!pal.dark) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), 0)
        }

        // Title row with the overlay switch on the right.
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = if (secretMode) "Secret" else "Notes"; textSize = 34f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toggle = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), 0, dp(16), 0)
            pressable(0.95f) { onToggle() }
        }
        if (!secretMode) {
            titleRow.addView(toggle, LinearLayout.LayoutParams(-2, dp(36)))
            titleRow.addView(iconButton(R.drawable.ic_settings, pal.secondary, "Settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        } else titleRow.addView(iconButton(R.drawable.ic_lock, pal.accent, "Lock now") {
            Vault.lock(); showGate()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        column.addView(titleRow)

        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), 0, dp(14))
        }
        statusDot = View(this)
        statusText = TextView(this).apply { textSize = 14f; setTextColor(pal.secondary) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        statusRow.addView(statusText)
        syncLine = TextView(this).apply {
            textSize = 13f; setTextColor(pal.secondary); visibility = View.GONE
            setPadding(dp(2), 0, 0, dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        if (!secretMode) { column.addView(statusRow); column.addView(syncLine) }

        permission = TextView(this).apply {
            text = "Allow display over other apps to use the edge bar"
            textSize = 14f; setTextColor(pal.accent)
            setPadding(dp(2), 0, 0, dp(14))
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        if (!secretMode) column.addView(permission)

        photoAccess = TextView(this).apply {
            text = "Allow photo access to attach screenshots"
            textSize = 14f; setTextColor(pal.accent)
            setPadding(dp(2), 0, 0, dp(14))
            setOnClickListener {
                if (photoDenied) startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                ) else requestPermissions(arrayOf(Screenshots.permission), 2)
            }
        }
        if (!secretMode) column.addView(photoAccess)

        // Search field.
        val searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = roundRect(pal.surface, dp(12).toFloat())
        }
        searchRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search); setColorFilter(pal.secondary)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        search = EditText(this).apply {
            hint = "Search"; textSize = 16f
            setSingleLine(true)
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            background = null
            setPadding(dp(8), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { renderNotes() }
            })
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))
        column.addView(searchRow, LinearLayout.LayoutParams(-1, dp(42)))

        chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chipScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; visibility = View.GONE
            setPadding(0, dp(10), 0, dp(10))
            addView(chips)
        }
        if (!secretMode) column.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))

        notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = PullScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(96))
            addView(notes)
        }
        column.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        // Floating "new note" button.
        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            val p = dp(16); setPadding(p, p, p, p)
            background = roundRect(pal.accent, dp(28).toFloat())
            elevation = dp(6).toFloat()
            contentDescription = "New note"
            pressable(0.92f) {
                startActivity(Intent(this@MainActivity, NoteActivity::class.java).putExtra(EXTRA_SECRET, secretMode))
            }
        }
        root = FrameLayout(this)
        setContentView(root.apply {
            setBackgroundColor(pal.background)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(20), dp(24)) })
        })

        if (secretMode) { showGate(); return }
        installPullUp(scroller)
        val ask = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
        if (!Screenshots.hasAccess(this)) ask += Screenshots.permission
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (!Screenshots.hasAccess(this)) {
            photoDenied = true
            photoAccess.text = "Open settings to allow photo access"
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (Palette(this).signature != pal.signature) { recreate(); return }   // the look changed (color setting, wallpaper)
        Sync.onChanged = { if (gate == null) renderNotes() }
        Sync.onStatus = { refreshSyncLine() }
        refreshSyncLine()
        Sync.schedule(this)
        Sync.syncIfStale(this)
        Live.acquire(this, "ui")
        if (secretMode) {
            Vault.returned()
            if (!Vault.isOpen()) showGate() else renderNotes()
            return
        }
        refreshStatus()
        renderNotes()
    }

    /**
     * Hidden on purpose. A normal pull down at the top just stretches the list, with no hint of a vault.
     * Only a long, deliberate pull shows the lock in the gap (with a click); letting go then opens the vault.
     * Pull back before releasing and it disappears again, so it can't be opened by accident.
     */
    private fun installPullUp(scroller: PullScrollView) {
        val hint = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(pal.accent)
            alpha = 0f
        }
        root.addView(hint, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val reveal = dp(150).toFloat()        // how far the list has been pulled down when the lock appears
        val slop = dp(8)
        var startY = 0f
        var pull = 0f
        var pulling = false
        var armed = false

        /** The lock sits in the gap, just above the list, and travels with it. */
        fun place() { hint.translationY = scroller.top + notes.translationY - dp(36) - dp(22) }
        fun arm(on: Boolean) {
            armed = on
            if (on) {
                Haptics.pulse(this, 1f)
                hint.scaleX = 0.6f; hint.scaleY = 0.6f
                Motion.spring(hint, DynamicAnimation.ALPHA, 1f, 0.8f, 0.25f)
                Motion.spring(hint, DynamicAnimation.SCALE_X, 1f, 0.6f, 0.3f)
                Motion.spring(hint, DynamicAnimation.SCALE_Y, 1f, 0.6f, 0.3f)
            } else Motion.spring(hint, DynamicAnimation.ALPHA, 0f, 1f, 0.2f)
        }
        fun settle() {
            if (armed) arm(false)
            Motion.spring(notes, DynamicAnimation.TRANSLATION_Y, 0f, 1f, 0.35f)
            pulling = false; pull = 0f
        }

        // Handled before the note cards see the touch, so a pull that starts on a note works too.
        scroller.pull = handler@{ ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = ev.rawY; pulling = false; armed = false; pull = 0f }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - startY
                    // Only a drag that begins at the very top counts, and nothing is consumed until the lock shows.
                    if (!pulling && dy > slop && !scroller.canScrollVertically(-1)) pulling = true
                    if (pulling) {
                        // The list follows the finger with growing resistance, leaving a gap above it.
                        val raw = (dy - slop).coerceAtLeast(0f)
                        pull = dp(330) * (1f - Math.exp(-raw / dp(330).toDouble()).toFloat()) * 0.75f
                        notes.translationY = pull
                        val now = pull >= reveal
                        if (now != armed) arm(now)
                        place()
                        return@handler true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                    val open = armed && ev.actionMasked == MotionEvent.ACTION_UP
                    settle()
                    if (open) {
                        startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_SECRET, true))
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                    return@handler true
                }
            }
            false
        }
    }


    private fun refreshSyncLine() {
        if (secretMode || !::syncLine.isInitialized) return
        val t = Sync.statusLine(this)
        syncLine.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        syncLine.text = "Sync: $t"
        syncLine.setTextColor(if (Sync.state == Sync.State.ATTENTION) pal.accent else pal.secondary)
    }

    private fun showGate() {
        gate?.let { root.removeView(it) }
        notes.removeAllViews()
        gate = VaultGate(this, pal, onUnlocked = {
            gate?.let { root.removeView(it) }; gate = null; renderNotes()
        }, onClose = { finish() }).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun onToggle() {
        if (!Settings.canDrawOverlays(this)) { permission.performClick(); return }
        val i = Intent(this, OverlayService::class.java)
        if (OverlayService.running) {
            i.action = OverlayService.ACTION_STOP
            startService(i)
        } else startForegroundService(i)
        toggle.postDelayed({ refreshStatus() }, 250)
    }

    private fun refreshStatus() {
        val canDraw = Settings.canDrawOverlays(this)
        val on = OverlayService.running
        permission.visibility = if (canDraw) View.GONE else View.VISIBLE
        photoAccess.visibility = if (Screenshots.hasAccess(this)) View.GONE else View.VISIBLE
        toggle.text = if (on) "Edge bar on" else "Edge bar off"
        toggle.setTextColor(if (on) pal.text else pal.onAccent)
        toggle.background = roundRect(
            if (on) pal.track else pal.accent,
            dp(18).toFloat()
        )
        statusDot.background = roundRect(
            if (on) pal.success else pal.secondary, dp(4).toFloat()
        )
        statusText.text = if (on) "Tap the bar on the screen edge from any app" else "Turn on to write notes over other apps"
    }

    private fun chip(label: String, on: Boolean, accent: Boolean = false, onHold: (() -> Unit)? = null, onTap: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (on) pal.onAccent else if (accent) pal.accent else pal.text)
            setPadding(dp(14), 0, dp(14), 0); gravity = Gravity.CENTER
            background = roundRect(if (on) pal.accent else pal.surface, dp(17).toFloat())
            pressable(0.95f) { onTap() }
            onHold?.let { h -> setOnLongClickListener { h(); true } }
        }

    private fun renderChips(all: List<Note>) {
        val names = Categories.names(this)
        if (filter != null && filter != "" && filter !in names) filter = null
        val uncategorized = all.count { it.category.isBlank() }
        chipScroll.visibility = if (secretMode || all.isEmpty()) View.GONE else View.VISIBLE
        chips.removeAllViews()
        fun add(v: View) = chips.addView(v, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })
        add(chip("All", filter == null) { filter = null; renderNotes() })
        names.forEach { n ->
            add(chip(n, filter == n, onHold = { Categories.manage(this, n) { renderNotes() } }) { filter = n; renderNotes() })
        }
        if (names.isNotEmpty() && uncategorized > 0) add(chip("None", filter == "") { filter = ""; renderNotes() })
        if (uncategorized > 0) add(chip(if (sorting) "Sorting…" else "✦ Sort with AI", false, accent = true) { sortWithAi() })
    }

    private fun sortWithAi() {
        if (sorting) return
        if (!Ai.configured(this)) {
            Toast.makeText(this, "Add your OpenRouter key in Settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        sorting = true
        renderNotes()
        Categories.sortWithAi(this) { filed, err ->
            sorting = false
            renderNotes()
            Toast.makeText(this,
                if (err != null) (if (filed > 0) "Sorted $filed notes. $err" else err)
                else if (filed == 0) "Nothing to sort" else "Sorted $filed notes", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderNotes() {
        notes.removeAllViews()
        val query = search.text.toString()
        if (!secretMode) renderChips(NoteStore.list(this))
        val list = NoteStore.search(this, query, secret = secretMode).filter {
            secretMode || filter == null || it.category == filter
        }
        if (list.isEmpty()) {
            notes.addView(TextView(this).apply {
                text = if (query.isBlank()) (if (secretMode) "No secret notes yet. Tap +." else "No notes yet. Tap + or use the edge bar.") else "No matches."
                textSize = 15f; setTextColor(pal.secondary)
                setPadding(dp(4), dp(16), 0, 0)
            })
            return
        }
        // Two staggered columns: each note goes to the shorter one, so cards of different heights pack neatly.
        val gap = dp(10)
        val cols = listOf(LinearLayout(this), LinearLayout(this)).onEach { it.orientation = LinearLayout.VERTICAL }
        val heights = floatArrayOf(0f, 0f)
        for (n in list) {
            val i = if (heights[0] <= heights[1]) 0 else 1
            heights[i] += estimateHeight(n)
            cols[i].addView(card(n), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }
        notes.addView(LinearLayout(this).apply {
            addView(cols[0], LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap / 2 })
            addView(cols[1], LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = gap / 2 })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Rough card height, only used to balance the two columns. */
    private fun estimateHeight(n: Note): Float {
        val lines = minOf(6, (n.preview.length / 18) + n.preview.count { it == '\n' })
        return 90f + lines * 22f + if (n.images.isNotEmpty()) 130f else 0f
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
        setPadding(dp(4), dp(10), 0, dp(8))
    }

    private fun card(n: Note): View {
        val body = n.preview
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = roundRect(pal.surface, dp(24).toFloat())
            addView(TextView(this@MainActivity).apply {
                text = android.text.SpannableString(n.heading.ifEmpty { "Untitled" })
                    .also { Checklist.style(it, pal, resources.displayMetrics.density) }
                textSize = 17f; setTextColor(pal.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                bidi()
            })
            if (n.images.isNotEmpty()) addView(imagePreview(n.images.first(), n.images.size),
                LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = if (body.isNotEmpty()) android.text.SpannableString(body)
                    .also { Checklist.style(it, pal, resources.displayMetrics.density) } else "No text"
                textSize = 15f; setTextColor(pal.secondary)
                maxLines = 6; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(0f, 1.1f)
                bidi()
            })
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = formatWhen(n.updatedAt); textSize = 13f; setTextColor(pal.secondary)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (n.category.isNotBlank() && filter != n.category) addView(TextView(this@MainActivity).apply {
                    text = n.category; textSize = 12f; setTextColor(pal.accent); maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(90)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = roundRect((pal.accent and 0x00FFFFFF) or 0x26000000, dp(9).toFloat())
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                if (n.pinned) addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin); setColorFilter(pal.accent)
                }, LinearLayout.LayoutParams(dp(16), dp(16)))
            })
            tapOrHoldToDelete(dp(24).toFloat(),
                onTap = {
                    startActivity(Intent(this@MainActivity, NoteActivity::class.java)
                        .putExtra(NoteActivity.EXTRA_ID, n.id).putExtra(EXTRA_SECRET, n.secret))
                },
                onDelete = { deleteWithUndo(n) })
        }
    }

    // ---- delete with undo ----

    private var undoNote: Note? = null
    private var undoBar: View? = null
    private val undoExpire = Runnable { finishDelete() }

    private fun deleteWithUndo(n: Note) {
        finishDelete()                       // a previous pending delete becomes final
        NoteStore.remove(this, n.id)
        undoNote = n
        renderNotes()
        showUndoBar()
        undoBar?.postDelayed(undoExpire, 5000)
    }

    private fun finishDelete() {
        undoBar?.removeCallbacks(undoExpire)
        undoNote?.let { NoteStore.purge(this, it) }
        undoNote = null
        hideUndoBar()
    }

    private fun showUndoBar() {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        hideUndoBar(animate = false)
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(6), 0)
            background = roundRect(pal.text, dp(24).toFloat())          // inverse of the screen: dark bar in light mode and the reverse
            elevation = dp(8).toFloat()
            addView(TextView(this@MainActivity).apply {
                text = "Note deleted"; textSize = 15f; setTextColor(pal.background)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "Undo"; textSize = 15f; setTextColor(pal.accentInverse)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                pressable(0.95f) {
                    undoBar?.removeCallbacks(undoExpire)
                    undoNote?.let { NoteStore.save(this@MainActivity, it) }
                    undoNote = null
                    hideUndoBar()
                    renderNotes()
                }
            })
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM)
            .apply { setMargins(dp(20), 0, dp(96), dp(28)) })
        bar.translationY = dp(90).toFloat()
        Motion.spring(bar, DynamicAnimation.TRANSLATION_Y, 0f, 1f, 0.35f)
        undoBar = bar
    }

    private fun hideUndoBar(animate: Boolean = true) {
        val bar = undoBar ?: return
        undoBar = null
        val root = bar.parent as? android.view.ViewGroup ?: return
        if (!animate) { root.removeView(bar); return }
        Motion.spring(bar, DynamicAnimation.TRANSLATION_Y, dp(90).toFloat(), 1f, 0.3f) { root.removeView(bar) }
    }

    override fun onStop() {
        finishDelete()
        Sync.onChanged = null
        Sync.onStatus = null
        Live.release("ui")
        if (secretMode) Vault.left()
        super.onStop()
    }

    /** Large rounded preview of a note's first image, with a "+N" chip when there are more. */
    private fun imagePreview(name: String, count: Int): View {
        val iv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.MATRIX    // cropped by hand, below, around the middle
            setBackgroundColor(pal.hairline)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
            }
            clipToOutline = true
            contentDescription = "Image in note"
            fun center() {
                val d = drawable ?: return
                if (width == 0 || height == 0) return
                val sc = maxOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
                imageMatrix = android.graphics.Matrix().apply {
                    setScale(sc, sc)
                    postTranslate((width - d.intrinsicWidth * sc) / 2f, (height - d.intrinsicHeight * sc) / 2f)
                }
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> center() }
            Attachments.thumb(this@MainActivity, name, resources.displayMetrics.widthPixels) {
                it?.let { b -> setImageBitmap(b); center() }
            }
        }
        return FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            if (count > 1) addView(TextView(this@MainActivity).apply {
                text = "+${count - 1}"; textSize = 13f; setTextColor(Color.WHITE)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = roundRect(0x99000000.toInt(), dp(10).toFloat())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) })
        }
    }

    companion object {
        const val EXTRA_SECRET = "secret"
    }
}

/** A ScrollView that offers every touch to [pull] first; once [pull] takes over, children get a cancel. */
class PullScrollView(ctx: android.content.Context) : ScrollView(ctx) {
    var pull: ((MotionEvent) -> Boolean)? = null
    private var taken = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val h = pull ?: return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) taken = false
        if (h(ev)) {
            if (!taken) {
                taken = true
                val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c)          // the card under the finger lets go
                c.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
