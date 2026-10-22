package com.scratchy.app

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** All settings, grouped into cards. Everything is stored on this phone. */
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

        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(40))
        }

        // ---- header ----
        page.addView(iconButton(R.drawable.ic_back, pal.text, "Back") { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(TextView(this).apply {
            text = "Settings"; textSize = 34f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
            setPadding(dp(14), dp(4), 0, 0)
        })

        // ---- EDGE BAR ----
        page.addView(groupHeader(pal, "Edge bar"))
        page.addView(GroupCard(this, pal).apply {
            row {
                title(pal, "Side")
                addView(SegmentedControl(context, pal, listOf("Left", "Right"),
                    if (prefs.getBoolean("right", false)) 1 else 0) { i ->
                    prefs.edit().putBoolean("right", i == 1).apply()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                caption(pal, "You can also long-press the bar and drag it across.")
            }
            row {
                addView(SliderRow(context, pal, "Distance from the edge", 0, 24,
                    prefs.getInt("bar_gap", 8).coerceIn(0, 24), { "$it dp" }) {
                    prefs.edit().putInt("bar_gap", it).apply()
                })
                caption(pal, "Move the bar in if your screen protector covers it.")
            }
        })

        // ---- CARD ----
        page.addView(groupHeader(pal, "Card"))
        page.addView(GroupCard(this, pal).apply {
            row {
                title(pal, "When the card opens")
                val modes = listOf("recent" to "Recent", "last" to "Last", "scratch" to "Scratch", "new" to "New")
                val help = mapOf(
                    "recent" to "Continues the note you last wrote in the card if you touched it in the last 30 minutes, otherwise starts a new one.",
                    "last" to "Always continues the last note you wrote in the card, however long ago.",
                    "scratch" to "Always opens one permanent, pinned note called Scratch and keeps adding to it.",
                    "new" to "Always starts a new blank note. Empty notes are never saved."
                )
                lateinit var note: TextView
                addView(SegmentedControl(context, pal, modes.map { it.second },
                    modes.indexOfFirst { it.first == CardNote.mode(context) }.coerceAtLeast(0)) { i ->
                    CardNote.setMode(context, modes[i].first)
                    note.text = help[modes[i].first]
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                note = caption(pal, help[CardNote.mode(context)].orEmpty())
            }
        })

        // ---- LOOK ----
        page.addView(groupHeader(pal, "Look"))
        val lookCard = GroupCard(this, pal)
        val sample = TextView(this).apply {
            text = "Your note"; textSize = 17f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        lateinit var opacityRow: SliderRow
        lateinit var blurRow: SliderRow
        lateinit var styleControl: SegmentedControl
        fun refreshLook() {
            sample.background = Appearance.card(this, pal)
            opacityRow.set(Appearance.opacity(this))
            blurRow.set(Appearance.blur(this))
        }
        lookCard.row {
            addView(FrameLayout(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFFFFB300.toInt(), 0xFFFF3D6E.toInt(), 0xFF7A3CFF.toInt(), 0xFF1E88E5.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
                clipToOutline = true
                addView(TextView(context).apply {
                    text = "Behind the card"; textSize = 22f; setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(16) })
                addView(sample, FrameLayout.LayoutParams(dp(170), dp(78), Gravity.CENTER_VERTICAL or Gravity.END)
                    .apply { marginEnd = dp(16) })
            }, LinearLayout.LayoutParams(-1, dp(120)))
        }
        lookCard.row {
            title(pal, "Style")
            styleControl = SegmentedControl(context, pal, listOf("Solid", "Liquid glass"),
                if (Appearance.isGlass(context)) 1 else 0) { i ->
                Appearance.setStyle(context, i == 1); refreshLook()
            }
            addView(styleControl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        lookCard.row {
            opacityRow = SliderRow(context, pal, "Opacity", 30, 100, Appearance.opacity(context), { "$it%" }) {
                Appearance.setOpacity(context, it); sample.background = Appearance.card(context, pal)
            }
            addView(opacityRow)
        }
        lookCard.row {
            blurRow = SliderRow(context, pal, "Blur whole screen behind", 0, 100, Appearance.blur(context), { "$it%" }) {
                Appearance.setBlur(context, it)
            }
            addView(blurRow)
            if (!Appearance.blurSupported(context)) {
                blurRow.enabledLook(false)
                caption(pal, "Blur isn't available on this phone right now (battery saver or developer options can switch it off).")
            } else {
                caption(pal, "Android can only blur the entire screen behind the card, not just under it. Leave it at 0% while you watch a video.")
            }
        }
        page.addView(lookCard)
        sample.background = Appearance.card(this, pal)

        // ---- ANIMATION ----
        page.addView(groupHeader(pal, "Animation"))
        lateinit var speedRow: SliderRow
        page.addView(GroupCard(this, pal).apply {
            row {
                title(pal, "Opening and closing")
                val styles = listOf("genie" to "Genie", "slide" to "Slide", "none" to "None")
                addView(SegmentedControl(context, pal, styles.map { it.second },
                    styles.indexOfFirst { it.first == prefs.getString("anim", "genie") }.coerceAtLeast(0)) { i ->
                    prefs.edit().putString("anim", styles[i].first).apply()
                    speedRow.enabledLook(styles[i].first == "genie")
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                caption(pal, "Genie funnels the card into the bar and back out. Slide slides it in from the edge.")
            }
            row {
                speedRow = SliderRow(context, pal, "Genie speed", 50, 200,
                    prefs.getInt("genie_speed", 100).coerceIn(50, 200), { "$it%" }) {
                    prefs.edit().putInt("genie_speed", it).apply()
                }
                speedRow.enabledLook(prefs.getString("anim", "genie") == "genie")
                addView(speedRow)
                caption(pal, "100% is the default. Lower is slower, higher is faster.")
            }
        })

        // ---- SCREENSHOTS ----
        page.addView(groupHeader(pal, "Screenshots"))
        page.addView(GroupCard(this, pal).apply {
            row {
                title(pal, "When you take one")
                val modes = listOf("ask" to "Ask", "auto" to "Auto-attach", "off" to "Off")
                val help = mapOf(
                    "ask" to "The bar turns green. Tap it and the card asks where it goes: current note, new note or another note.",
                    "auto" to "Added to the open note on its own. If the card is closed, the next note you open gets it.",
                    "off" to "Ignored. The screenshot button in the card still attaches your latest one on demand."
                )
                lateinit var note: TextView
                addView(SegmentedControl(context, pal, modes.map { it.second },
                    modes.indexOfFirst { it.first == prefs.getString("shot_mode", "ask") }.coerceAtLeast(0)) { i ->
                    prefs.edit().putString("shot_mode", modes[i].first).apply()
                    note.text = help[modes[i].first]
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                note = caption(pal, help[prefs.getString("shot_mode", "ask")].orEmpty())
            }
            row {
                title(pal, "Forget a waiting screenshot after")
                val ttls = listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
                addView(SegmentedControl(context, pal, ttls.map { it.second },
                    ttls.indexOfFirst { it.first == prefs.getInt("shot_ttl", 60) }.coerceAtLeast(0)) { i ->
                    prefs.edit().putInt("shot_ttl", ttls[i].first).apply()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                caption(pal, "After this long the card stops offering it and the bar goes back to normal.")
            }
        })

        // ---- AI TITLES ----
        page.addView(groupHeader(pal, "AI titles"))
        fun field(name: String, hint: String, secret: Boolean): EditText = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            textSize = 16f
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(trackColor(pal), dp(12).toFloat())
            setText(AiSettings.raw(this@SettingsActivity, name))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) = AiSettings.set(this@SettingsActivity, name, e.toString())
            })
        }
        page.addView(GroupCard(this, pal).apply {
            row {
                caption(pal, "Tap the ✨ next to a note's title and Scratchy suggests three titles from its text, or from its first image. Only that note is sent, only when you tap, to OpenRouter.")
            }
            row {
                title(pal, "OpenRouter key")
                val key = field("key", "Paste your key", secret = true)
                addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                addView(TextView(context).apply {
                    text = "Paste from clipboard"; textSize = 14f; setTextColor(pal.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(2), dp(10), dp(8), dp(2))
                    pressable(0.97f) {
                        val t = getSystemService(ClipboardManager::class.java).primaryClip
                            ?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString().orEmpty()
                        if (t.isNotBlank()) key.setText(t.trim())
                    }
                })
            }
            row {
                title(pal, "Text model")
                addView(field("textModel", AiSettings.DEFAULT_MODEL, secret = false),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            row {
                title(pal, "Image model")
                addView(field("imageModel", "Same as text model", secret = false),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                caption(pal, "Must understand images. Notes with an image use this one.")
            }
            row {
                val result = TextView(context).apply {
                    textSize = 14f; setTextColor(pal.secondary)
                    setPadding(dp(2), dp(10), 0, 0)
                }
                addView(TextView(context).apply {
                    text = "Test connection"; textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = roundRect(pal.accent, dp(14).toFloat())
                    pressable(0.97f) {
                        result.text = "Testing…"
                        Ai.ping(this@SettingsActivity) { result.text = it }
                    }
                }, LinearLayout.LayoutParams(-1, dp(46)))
                addView(result)
            }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }
}
