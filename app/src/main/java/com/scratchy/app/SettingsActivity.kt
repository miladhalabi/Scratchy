package com.scratchy.app

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** AI title suggestions: key and model names. Everything stays on this phone. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val pal = Palette(this)
        setTheme(
            if (pal.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        window.statusBarColor = pal.background
        window.navigationBarColor = pal.background
        if (!pal.dark) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_back, pal.text, "Back") { finish() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        col.addView(TextView(this).apply {
            text = "Settings"; textSize = 34f; setTextColor(pal.text)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            letterSpacing = -0.02f
            setPadding(dp(2), dp(8), 0, dp(6))
        })
        // ---- edge bar side ----
        col.addView(TextView(this).apply {
            text = "EDGE BAR"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        val overlayPrefs = getSharedPreferences("overlay", MODE_PRIVATE)
        val left = TextView(this); val right = TextView(this)
        fun paint() {
            val isRight = overlayPrefs.getBoolean("right", false)
            for ((v, label, on) in listOf(Triple(left, "Left", !isRight), Triple(right, "Right", isRight))) {
                v.text = label; v.textSize = 16f; v.gravity = Gravity.CENTER
                v.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                v.setTextColor(if (on) android.graphics.Color.WHITE else pal.text)
                v.background = if (on) roundRect(pal.accent, dp(10).toFloat()) else null
            }
        }
        left.pressable(0.97f) { overlayPrefs.edit().putBoolean("right", false).apply(); paint() }
        right.pressable(0.97f) { overlayPrefs.edit().putBoolean("right", true).apply(); paint() }
        paint()
        col.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(pal.surface, dp(14).toFloat())
            addView(left, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(right, LinearLayout.LayoutParams(0, dp(40), 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            text = "Which side of the screen the bar sits on. You can also long-press the bar and drag it across."
            textSize = 13f; setTextColor(pal.secondary)
            setPadding(dp(4), dp(8), 0, dp(22))
        })

        // ---- what the card opens on ----
        col.addView(TextView(this).apply {
            text = "WHEN THE CARD OPENS"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        val modes = listOf("recent" to "Recent", "last" to "Last", "scratch" to "Scratch", "new" to "New")
        val modeHelp = mapOf(
            "recent" to "Continues the note you last wrote in the card if you touched it in the last 30 minutes, otherwise starts a new one.",
            "last" to "Always continues the last note you wrote in the card, however long ago.",
            "scratch" to "Always opens one permanent, pinned note called Scratch and keeps adding to it.",
            "new" to "Always starts a new blank note. Empty notes are never saved."
        )
        val modeCells = modes.map { TextView(this) }
        val modeNote = TextView(this).apply {
            textSize = 13f; setTextColor(pal.secondary); setPadding(dp(4), dp(8), 0, dp(22))
        }
        fun paintModes() {
            val cur = CardNote.mode(this)
            modes.forEachIndexed { i, (key, label) ->
                val on = key == cur
                modeCells[i].apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(if (on) android.graphics.Color.WHITE else pal.text)
                    background = if (on) roundRect(pal.accent, dp(10).toFloat()) else null
                }
            }
            modeNote.text = modeHelp[cur]
        }
        modes.forEachIndexed { i, (key, _) -> modeCells[i].pressable(0.97f) { CardNote.setMode(this, key); paintModes() } }
        paintModes()
        col.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(pal.surface, dp(14).toFloat())
            modeCells.forEach { addView(it, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(modeNote)

        // ---- screenshots ----
        col.addView(TextView(this).apply {
            text = "SCREENSHOTS"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        val shotModes = listOf("ask" to "Ask", "auto" to "Auto-attach", "off" to "Off")
        val shotHelp = mapOf(
            "ask" to "The bar turns green when you take a screenshot. Tap it and the card asks where it goes: current note, new note or another note. Nothing is added unless you choose.",
            "auto" to "Screenshots are added to the open note on their own. If the card is closed, the next note you open gets it.",
            "off" to "Screenshots are ignored. The screenshot button in the card still attaches your latest one when you want."
        )
        val shotCells = shotModes.map { TextView(this) }
        val shotNote = TextView(this).apply {
            textSize = 13f; setTextColor(pal.secondary); setPadding(dp(4), dp(8), 0, dp(22))
        }
        fun paintShot() {
            val cur = overlayPrefs.getString("shot_mode", "ask")
            shotModes.forEachIndexed { i, (key, label) ->
                val on = key == cur
                shotCells[i].apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(if (on) android.graphics.Color.WHITE else pal.text)
                    background = if (on) roundRect(pal.accent, dp(10).toFloat()) else null
                }
            }
            shotNote.text = shotHelp[cur]
        }
        shotModes.forEachIndexed { i, (key, _) ->
            shotCells[i].pressable(0.97f) { overlayPrefs.edit().putString("shot_mode", key).apply(); paintShot() }
        }
        paintShot()
        col.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(pal.surface, dp(14).toFloat())
            shotCells.forEach { addView(it, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(shotNote)

        // ---- open / close animation ----
        col.addView(TextView(this).apply {
            text = "ANIMATION"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        val styles = listOf("genie" to "Genie", "slide" to "Slide", "none" to "None")
        val cells = styles.map { TextView(this) }
        fun paintStyles() {
            val cur = overlayPrefs.getString("anim", "genie")
            styles.forEachIndexed { i, (key, label) ->
                val on = key == cur
                cells[i].apply {
                    text = label; textSize = 16f; gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(if (on) android.graphics.Color.WHITE else pal.text)
                    background = if (on) roundRect(pal.accent, dp(10).toFloat()) else null
                }
            }
        }
        styles.forEachIndexed { i, (key, _) ->
            cells[i].pressable(0.97f) { overlayPrefs.edit().putString("anim", key).apply(); paintStyles() }
        }
        paintStyles()
        col.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(pal.surface, dp(14).toFloat())
            cells.forEach { addView(it, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        }, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            text = "Genie: the card funnels into the bar and grows back out of it. Slide: it slides in from the edge. None: instant."
            textSize = 13f; setTextColor(pal.secondary)
            setPadding(dp(4), dp(8), 0, dp(22))
        })

        // ---- card look: style, opacity, blur, with a live preview ----
        col.addView(TextView(this).apply {
            text = "CARD LOOK"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(8))
        })
        val preview = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFFB300.toInt(), 0xFFFF3D6E.toInt(), 0xFF7A3CFF.toInt(), 0xFF1E88E5.toInt())
            ).apply { cornerRadius = dp(18).toFloat() }
            clipToOutline = true
            addView(TextView(this@SettingsActivity).apply {
                text = "Behind the card"; textSize = 26f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START)
                .apply { marginStart = dp(18) })
        }
        val sample = TextView(this).apply {
            text = "Your note"; textSize = 17f; setTextColor(pal.text)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        preview.addView(sample, android.widget.FrameLayout.LayoutParams(dp(190), dp(96), Gravity.CENTER_VERTICAL or Gravity.END)
            .apply { marginEnd = dp(20) })
        col.addView(preview, LinearLayout.LayoutParams(-1, dp(150)))

        val solidBtn = TextView(this); val glassBtn = TextView(this)
        val opacityLabel = TextView(this); val blurLabel = TextView(this)
        val opacityBar = android.widget.SeekBar(this).apply { max = 70 }
        val blurBar = android.widget.SeekBar(this).apply { max = 100 }
        val tint = android.content.res.ColorStateList.valueOf(pal.accent)
        for (b in listOf(opacityBar, blurBar)) { b.progressTintList = tint; b.thumbTintList = tint }

        fun refreshCard() {
            val glass = Appearance.isGlass(this)
            for ((v, label, on) in listOf(Triple(solidBtn, "Solid", !glass), Triple(glassBtn, "Liquid glass", glass))) {
                v.text = label; v.textSize = 16f; v.gravity = Gravity.CENTER
                v.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                v.setTextColor(if (on) android.graphics.Color.WHITE else pal.text)
                v.background = if (on) roundRect(pal.accent, dp(10).toFloat()) else null
            }
            opacityLabel.text = "Opacity   ${Appearance.opacity(this)}%"
            blurLabel.text = "Blur whole screen behind   ${Appearance.blur(this)}%"
            opacityBar.progress = Appearance.opacity(this) - 30
            blurBar.progress = Appearance.blur(this)
            sample.background = Appearance.card(this, pal)
            sample.setTextColor(pal.text)
        }
        solidBtn.pressable(0.97f) { Appearance.setStyle(this, false); refreshCard() }
        glassBtn.pressable(0.97f) { Appearance.setStyle(this, true); refreshCard() }
        opacityBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                Appearance.setOpacity(this@SettingsActivity, p + 30)
                opacityLabel.text = "Opacity   ${p + 30}%"
                sample.background = Appearance.card(this@SettingsActivity, pal)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        blurBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                Appearance.setBlur(this@SettingsActivity, p)
                blurLabel.text = "Blur whole screen behind   $p%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })
        col.addView(LinearLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundRect(pal.surface, dp(14).toFloat())
            addView(solidBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(glassBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        fun sliderLabel(t: TextView) = t.apply {
            textSize = 15f; setTextColor(pal.text); setPadding(dp(4), dp(16), 0, dp(2))
        }
        col.addView(sliderLabel(opacityLabel))
        col.addView(opacityBar, LinearLayout.LayoutParams(-1, dp(36)))
        col.addView(sliderLabel(blurLabel))
        col.addView(blurBar, LinearLayout.LayoutParams(-1, dp(36)))
        val blurOk = Appearance.blurSupported(this)
        if (!blurOk) {
            blurBar.isEnabled = false; blurBar.alpha = 0.4f
            col.addView(TextView(this).apply {
                text = "Blur isn't available on this phone right now (it can be switched off by battery saver or developer options)."
                textSize = 13f; setTextColor(pal.secondary); setPadding(dp(4), dp(4), 0, 0)
            })
        } else {
            col.addView(TextView(this).apply {
                text = "Opacity and blur apply the next time the card opens. Android can only blur the entire screen behind the card, not just the area under it, so leave this at 0% while you watch a video."
                textSize = 13f; setTextColor(pal.secondary); setPadding(dp(4), dp(6), 0, 0)
            })
        }
        col.addView(View(this), LinearLayout.LayoutParams(-1, dp(22)))
        refreshCard()

        col.addView(TextView(this).apply {
            text = "AI TITLES"; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(8), 0, dp(6))
        })
        col.addView(TextView(this).apply {
            text = "Tap the ✨ next to a note's title and Scratchy suggests three titles from its text, " +
                "or from its first image. Only that note is sent, only when you tap, to OpenRouter."
            textSize = 14f; setTextColor(pal.secondary); setLineSpacing(0f, 1.1f)
            setPadding(dp(2), 0, 0, dp(18))
        })

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(pal.secondary); letterSpacing = 0.08f
            setPadding(dp(4), dp(10), 0, dp(6))
        }
        fun field(name: String, hint: String, secret: Boolean): EditText = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            textSize = 16f
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(pal.surface, dp(12).toFloat())
            setText(AiSettings.raw(this@SettingsActivity, name))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) = AiSettings.set(this@SettingsActivity, name, e.toString())
            })
        }

        col.addView(label("OPENROUTER KEY"))
        val key = field("key", "Paste your key", secret = true)
        col.addView(key, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            text = "Paste from clipboard"; textSize = 15f; setTextColor(pal.accent)
            setPadding(dp(4), dp(10), 0, dp(4))
            pressable(0.97f) {
                val t = getSystemService(ClipboardManager::class.java).primaryClip
                    ?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString().orEmpty()
                if (t.isNotBlank()) key.setText(t.trim())
            }
        })

        col.addView(label("TEXT MODEL"))
        col.addView(field("textModel", AiSettings.DEFAULT_MODEL, secret = false), LinearLayout.LayoutParams(-1, -2))
        col.addView(label("IMAGE MODEL (must understand images)"))
        col.addView(field("imageModel", "Same as text model", secret = false), LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            text = "If the text model can't read images, put an image-capable model id here. " +
                "Notes with an image use this one."
            textSize = 13f; setTextColor(pal.secondary)
            setPadding(dp(4), dp(8), 0, dp(14))
        })

        val result = TextView(this).apply {
            textSize = 14f; setTextColor(pal.secondary)
            setPadding(dp(4), dp(10), 0, 0)
        }
        col.addView(TextView(this).apply {
            text = "Test connection"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = roundRect(pal.accent, dp(24).toFloat())
            pressable(0.97f) {
                result.text = "Testing…"
                Ai.ping(this@SettingsActivity) { result.text = it }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        col.addView(result)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.background)
            isFillViewport = true
            addView(col)
        })
    }
}
