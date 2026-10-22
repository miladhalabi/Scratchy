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
import android.view.View
import android.content.pm.PackageManager
import androidx.dynamicanimation.animation.DynamicAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Palette(this)
        setTheme(
            if (pal.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
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
            text = "Notes"; textSize = 34f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toggle = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), 0, dp(16), 0)
            pressable(0.95f) { onToggle() }
        }
        titleRow.addView(toggle, LinearLayout.LayoutParams(-2, dp(36)))
        titleRow.addView(iconButton(R.drawable.ic_settings, pal.secondary, "Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        column.addView(titleRow)

        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), 0, dp(14))
        }
        statusDot = View(this)
        statusText = TextView(this).apply { textSize = 14f; setTextColor(pal.secondary) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        statusRow.addView(statusText)
        column.addView(statusRow)

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
        column.addView(permission)

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
        column.addView(photoAccess)

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

        notes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, dp(8), 0, dp(96))
                addView(notes)
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Floating "new note" button.
        val fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            val p = dp(16); setPadding(p, p, p, p)
            background = roundRect(pal.accent, dp(28).toFloat())
            elevation = dp(6).toFloat()
            contentDescription = "New note"
            pressable(0.92f) { startActivity(Intent(this@MainActivity, NoteActivity::class.java)) }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(pal.background)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(20), dp(24)) })
        })

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
        refreshStatus()
        renderNotes()
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
        toggle.setTextColor(if (on) pal.text else Color.WHITE)
        toggle.background = roundRect(
            if (on) (if (pal.dark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")) else pal.accent,
            dp(18).toFloat()
        )
        statusDot.background = roundRect(
            if (on) Color.parseColor("#34C759") else pal.secondary, dp(4).toFloat()
        )
        statusText.text = if (on) "Tap the bar on the screen edge from any app" else "Turn on to write notes over other apps"
    }

    private fun renderNotes() {
        notes.removeAllViews()
        val query = search.text.toString()
        val list = NoteStore.search(this, query)
        if (list.isEmpty()) {
            notes.addView(TextView(this).apply {
                text = if (query.isBlank()) "No notes yet. Tap + or use the edge bar." else "No matches."
                textSize = 15f; setTextColor(pal.secondary)
                setPadding(dp(4), dp(16), 0, 0)
            })
            return
        }
        var lastPinned: Boolean? = null
        for (n in list) {
            if (n.pinned != lastPinned && (n.pinned || lastPinned == true)) {
                notes.addView(sectionLabel(if (n.pinned) "PINNED" else "NOTES"))
            }
            lastPinned = n.pinned
            notes.addView(card(n), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
        setPadding(dp(4), dp(10), 0, dp(8))
    }

    private fun card(n: Note): View {
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = android.text.SpannableString(n.heading.ifEmpty { "Untitled" })
                    .also { Checklist.style(it, pal, resources.displayMetrics.density) }
                textSize = 17f; setTextColor(pal.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                bidi()
            })
            if (n.preview.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = android.text.SpannableString(n.preview)
                    .also { Checklist.style(it, pal, resources.displayMetrics.density) }
                textSize = 15f; setTextColor(pal.secondary)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
                bidi()
            })
            addView(TextView(this@MainActivity).apply {
                text = formatWhen(n.updatedAt); textSize = 13f; setTextColor(pal.secondary)
                setPadding(0, dp(8), 0, 0)
            })
        }
        val pin = iconButton(R.drawable.ic_pin, if (n.pinned) pal.accent else pal.hairline, "Pin") {
            NoteStore.save(this, n.copy(pinned = !n.pinned)); renderNotes()
        }
        val topRow = LinearLayout(this).apply {
            addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pin, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            background = roundRect(pal.surface, dp(16).toFloat())
            addView(topRow, LinearLayout.LayoutParams(-1, -2))
            n.images.firstOrNull()?.let { name -> addView(imagePreview(name, n.images.size),
                LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(10); marginEnd = dp(8) }) }
            tapOrHoldToDelete(dp(16).toFloat(),
                onTap = {
                    startActivity(Intent(this@MainActivity, NoteActivity::class.java)
                        .putExtra(NoteActivity.EXTRA_ID, n.id))
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
            background = roundRect(if (pal.dark) Color.parseColor("#3A3A3C") else Color.parseColor("#2C2C2E"), dp(24).toFloat())
            elevation = dp(8).toFloat()
            addView(TextView(this@MainActivity).apply {
                text = "Note deleted"; textSize = 15f; setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "Undo"; textSize = 15f; setTextColor(pal.accent)
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
        super.onStop()
    }

    /** Large rounded preview of a note's first image, with a "+N" chip when there are more. */
    private fun imagePreview(name: String, count: Int): View {
        val iv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(pal.hairline)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
            }
            clipToOutline = true
            contentDescription = "Image in note"
            Attachments.thumb(this@MainActivity, name, resources.displayMetrics.widthPixels) {
                it?.let(::setImageBitmap)
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
}
