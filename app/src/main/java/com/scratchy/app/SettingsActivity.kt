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
import android.widget.Toast

/** All settings, grouped into cards. Everything is stored on this phone. */
class SettingsActivity : Activity() {

    private lateinit var pal: Palette
    private val pageName by lazy { intent.getStringExtra(EXTRA_PAGE) }

    private var syncUi: () -> Unit = {}
    private var syncBusy = false
    private var syncPassword: String? = null
    private lateinit var syncNewPw: TextView

    private fun passwordDialog(title: String, message: String, hints: List<String>, onOk: (List<String>) -> Unit) {
        Sheet(this, pal).title(title).message(message).apply { hints.forEach { field(it, password = true) } }
            .confirm("OK", onOk = onOk).show()
    }

    private fun changePassword() {
        if (!Vault.isSetUp(this)) {
            Toast.makeText(this, "No vault password yet. Pull down the notes list to set one.", Toast.LENGTH_LONG).show(); return
        }
        passwordDialog("Change vault password", "Enter your current password, then the new one.",
            listOf("Current password", "New password", "Repeat new password")) { (old, new, again) ->
            when {
                !Vault.checkPassword(this, old) -> Toast.makeText(this, "Current password is wrong", Toast.LENGTH_SHORT).show()
                new.length < 4 -> Toast.makeText(this, "Use at least 4 characters", Toast.LENGTH_SHORT).show()
                new != again -> Toast.makeText(this, "The new passwords don't match", Toast.LENGTH_SHORT).show()
                !Sync.connected(this) -> { Vault.setPassword(this, new); Toast.makeText(this, "Password changed", Toast.LENGTH_SHORT).show() }
                else -> {
                    syncBusy = true; syncUi()
                    Sync.rekey(this, new) { out ->
                        syncBusy = false
                        if (out.ok) Vault.setPassword(this, new)         // only once Drive has the new key
                        Toast.makeText(this, if (out.ok) "Password changed" else out.message, Toast.LENGTH_LONG).show()
                        syncUi()
                    }
                }
            }
        }
    }

    /** This device missed a password change made elsewhere: take the new password and rejoin. */
    private fun enterNewPassword() {
        passwordDialog("New vault password", "Enter the vault password you set on your other device.",
            listOf("New password")) { (pw) ->
            syncBusy = true; syncUi()
            Sync.run(this, pw) { out ->
                syncBusy = false
                if (out.ok) Vault.setPassword(this, pw)
                else Toast.makeText(this, out.message, Toast.LENGTH_LONG).show()
                syncUi()
            }
        }
    }

    /** Connects (asks for the vault password) or, when already connected, syncs now. */
    private fun startSync() {
        if (syncBusy) return
        if (Sync.connected(this)) { runSync(null); return }
        if (!Vault.isSetUp(this)) {
            Toast.makeText(this, "Set a vault password first: pull down the notes list", Toast.LENGTH_LONG).show()
            return
        }
        Sheet(this, pal).title("Connect Google Drive")
            .message("Enter your vault password. It encrypts your notes before they leave this phone.")
            .field("Vault password", password = true)
            .confirm("Connect") { (pw) ->
                if (Vault.checkPassword(this, pw)) runSync(pw)
                else Toast.makeText(this, "Wrong vault password", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun runSync(password: String?) {
        syncBusy = true; syncPassword = password; syncUi()
        Sync.run(this, password) { out ->
            syncBusy = false
            val consent = out.consent
            if (consent != null) {          // Google wants the user to approve Drive access
                runCatching {
                    startIntentSenderForResult(consent.intentSender, REQ_CONSENT, null, 0, 0, 0)
                }.onFailure { Toast.makeText(this, "Couldn't open Google sign-in", Toast.LENGTH_LONG).show() }
            } else if (!out.ok) Toast.makeText(this, out.message, Toast.LENGTH_LONG).show()
            syncUi()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONSENT) {
            val why = runCatching {
                com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)
                null
            }.getOrElse { e ->
                (e as? com.google.android.gms.common.api.ApiException)
                    ?.let { "code ${it.statusCode} ${com.google.android.gms.common.api.CommonStatusCodes.getStatusCodeString(it.statusCode)}" }
                    ?: e.javaClass.simpleName + " " + e.message
            }
            android.util.Log.w("ScratchySync", "consent result=$resultCode why=$why data=$data")
            if (resultCode == RESULT_OK && why == null) runSync(syncPassword)
            else Toast.makeText(this, "Drive access wasn't allowed ($why, result $resultCode)", Toast.LENGTH_LONG).show()
        }
    }

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

        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(40))
        }
        val titles = mapOf(
            "bar" to "Edge bar", "card" to "Card", "look" to "Appearance", "shots" to "Screenshots",
            "sync" to "Sync", "vault" to "Secret notes", "ai" to "AI titles",
        )

        // ---- header ----
        page.addView(iconButton(R.drawable.ic_back, pal.text, "Back") { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(TextView(this).apply {
            text = titles[pageName] ?: "Settings"; textSize = 34f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
            setPadding(dp(14), dp(4), 0, dp(14))
        })

        when (pageName) {
            "bar" -> buildBar(page, prefs)
            "card" -> buildCard(page)
            "look" -> buildLook(page, prefs)
            "shots" -> buildShots(page, prefs)
            "sync" -> buildSync(page)
            "vault" -> buildVault(page)
            "ai" -> buildAi(page)
            else -> buildMenu(page, prefs)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        if (Palette(this).signature != pal.signature) { recreate(); return }
        syncUi()
        if (pageName == null) { /* the menu summaries can change after visiting a page */ recreateMenuIfStale() }
    }

    private var menuSnapshot: String? = null
    private fun menuState(): String = listOf(
        getSharedPreferences("overlay", MODE_PRIVATE).all.toString(), Sync.statusLine(this), Vault.isSetUp(this), AiSettings.raw(this, "key").isNotBlank()
    ).joinToString("|")
    private fun recreateMenuIfStale() {
        val now = menuState()
        if (menuSnapshot != null && menuSnapshot != now) { recreate(); return }
        menuSnapshot = now
    }

    private fun open(page: String) = startActivity(android.content.Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page))

    // ---- the menu ----

    private fun buildMenu(page: LinearLayout, prefs: android.content.SharedPreferences) {
        val modeNames = mapOf("recent" to "Recent", "last" to "Last", "scratch" to "Scratch", "new" to "New")
        val shotNames = mapOf("ask" to "Ask", "auto" to "Auto-attach", "off" to "Off")
        val colorName = Themes.labels[Themes.names.indexOf(pal.theme).coerceAtLeast(0)]
        page.addView(GroupCard(this, pal).apply {
            row {
                navRow(pal, R.drawable.ic_grip, "Edge bar",
                    (if (prefs.getBoolean("right", false)) "Right side" else "Left side") + " · ${prefs.getInt("bar_gap", 8)} dp from the edge") { open("bar") }
            }
            row { navRow(pal, R.drawable.ic_pen, "Card", "Opens on: ${modeNames[CardNote.mode(context)] ?: "Recent"}") { open("card") } }
            row {
                navRow(pal, R.drawable.ic_palette, "Appearance",
                    "$colorName · " + (if (Appearance.isGlass(context)) "Glass" else "Solid") + " card") { open("look") }
            }
            row { navRow(pal, R.drawable.ic_screenshot, "Screenshots", shotNames[prefs.getString("shot_mode", "ask")] ?: "Ask") { open("shots") } }
        })
        page.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
        page.addView(GroupCard(this, pal).apply {
            row {
                navRow(pal, R.drawable.ic_sync, "Sync",
                    Sync.statusLine(context).ifEmpty { "Not connected" }) { open("sync") }
            }
            row {
                navRow(pal, R.drawable.ic_lock, "Secret notes",
                    if (Vault.isSetUp(context)) "Password set" + (if (Vault.biometricEnabled(context)) " · fingerprint on" else "")
                    else "Not set up yet") { open("vault") }
            }
            row {
                navRow(pal, R.drawable.ic_sparkle, "AI titles",
                    if (AiSettings.raw(context, "key").isNotBlank()) "Key added" else "No key yet") { open("ai") }
            }
        })
        menuSnapshot = menuState()
    }

    // ---- pages ----

    private fun buildBar(page: LinearLayout, prefs: android.content.SharedPreferences) {
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
    }

    private fun buildCard(page: LinearLayout) {
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
    }

    private fun buildLook(page: LinearLayout, prefs: android.content.SharedPreferences) {
        // Color
        page.addView(groupHeader(pal, "Color"))
        page.addView(GroupCard(this, pal).apply {
            row {
                addView(ColorSwatches(context, pal, pal.theme) { name ->
                    prefs.edit().putString("theme_color", name).apply()
                    recreate()
                })
                caption(pal, "Wallpaper follows your phone's colors (Android 12 and newer). If it doesn't look right on your phone, pick a color.")
                    .setPadding(0, dp(8), 0, 0)
            }
        })

        // Card look
        page.addView(groupHeader(pal, "Card"))
        val lookCard = GroupCard(this, pal)
        // A small stand-in for a note card, drawn with the real card look so Style / Opacity changes show at once.
        fun bar(widthDp: Int) = View(this).apply { background = roundRect(pal.secondary and 0xFFFFFF or 0x66000000, dp(4).toFloat()) }
            .also { it.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(8)).apply { topMargin = dp(8) } }
        val sample = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(TextView(context).apply {
                text = "Your note"; textSize = 16f; setTextColor(pal.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(bar(150)); addView(bar(110))
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
                ).apply { cornerRadius = dp(16).toFloat() }
                clipToOutline = true
                addView(sample, FrameLayout.LayoutParams(dp(220), dp(104), Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, dp(150)))
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

        // Animation
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
            advanced {
                row {
                    speedRow = SliderRow(context, pal, "Genie speed", 50, 200,
                        prefs.getInt("genie_speed", 100).coerceIn(50, 200), { "$it%" }) {
                        prefs.edit().putInt("genie_speed", it).apply()
                    }
                    speedRow.enabledLook(prefs.getString("anim", "genie") == "genie")
                    addView(speedRow)
                    caption(pal, "100% is the default. Lower is slower, higher is faster.")
                }
            }
        })
    }

    private fun buildShots(page: LinearLayout, prefs: android.content.SharedPreferences) {
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
            advanced {
                row {
                    title(pal, "Forget a waiting screenshot after")
                    val ttls = listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
                    addView(SegmentedControl(context, pal, ttls.map { it.second },
                        ttls.indexOfFirst { it.first == prefs.getInt("shot_ttl", 60) }.coerceAtLeast(0)) { i ->
                        prefs.edit().putInt("shot_ttl", ttls[i].first).apply()
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                    caption(pal, "After this long the card stops offering it and the bar goes back to normal.")
                }
            }
        })
    }

    private fun buildSync(page: LinearLayout) {
        val syncStatus = TextView(this).apply { textSize = 14f; setTextColor(pal.secondary); setPadding(dp(2), dp(10), 0, 0) }
        val syncMain = TextView(this)
        val syncOff = TextView(this)
        syncNewPw = TextView(this)
        fun syncButton(t: TextView, filled: Boolean, onTap: () -> Unit) = t.apply {
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(if (filled) pal.onAccent else pal.accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = if (filled) roundRect(pal.accent, dp(14).toFloat()) else null
            pressable(0.97f) { onTap() }
        }
        syncUi = {
            val on = Sync.connected(this)
            syncMain.text = if (on) "Sync now" else "Connect Google Drive"
            syncOff.visibility = if (on) View.VISIBLE else View.GONE
            syncNewPw.visibility = if (Sync.needsNewPassword(this)) View.VISIBLE else View.GONE
            syncStatus.text = if (syncBusy) "Syncing…" else Sync.status(this).ifEmpty { if (on) "Connected" else "Not connected" }
        }
        page.addView(GroupCard(this, pal).apply {
            row {
                caption(pal, "Keeps your notes, secret notes and images in sync across your devices through the hidden app folder of your Google Drive. Everything is encrypted on the phone first with a key made from your vault password, so Google can't read it. Use the same vault password on every device. If you forget it, the synced copy can't be recovered.")
            }
            row {
                addView(syncButton(syncMain, true) { startSync() }, LinearLayout.LayoutParams(-1, dp(46)))
                addView(syncStatus)
                addView(syncNewPw.apply {
                    text = "Enter the new vault password"; textSize = 15f; setTextColor(pal.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(2), dp(10), dp(8), dp(2))
                    pressable(0.97f) { enterNewPassword() }
                })
                addView(syncButton(syncOff, false) {
                    Sync.disconnect(this@SettingsActivity); syncUi()
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            }
        })
        syncUi()
    }

    private fun buildVault(page: LinearLayout) {
        page.addView(GroupCard(this, pal).apply {
            row {
                caption(pal, "Secret notes are hidden from every list and search, and their text is encrypted on this phone. Open them by pulling the notes list down from the top, far enough that a lock appears, then letting go.")
            }
            row {
                title(pal, "Vault password")
                caption(pal, "Opens secret notes and encrypts your synced notes. Changing it re-encrypts everything in Drive; enter the new one on your other devices afterwards.")
                addView(TextView(context).apply {
                    text = "Change vault password"; textSize = 15f; setTextColor(pal.accent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(2), dp(10), dp(8), dp(2))
                    pressable(0.97f) { changePassword() }
                })
            }
        })
        syncNewPw = TextView(this)
    }

    private fun buildAi(page: LinearLayout) {
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
                title(pal, "Suggest a category")
                addView(SegmentedControl(context, pal, listOf("On", "Off"),
                    if (AiSettings.suggestCategories(this@SettingsActivity)) 0 else 1) { i ->
                    AiSettings.setSuggestCategories(this@SettingsActivity, i == 0)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                caption(pal, "While you write a new note, a small chip offers a category. Tap it to accept, or ignore it and it fades away. Needs the key below.")
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
                val result = TextView(context).apply {
                    textSize = 14f; setTextColor(pal.secondary)
                    setPadding(dp(2), dp(10), 0, 0)
                }
                addView(TextView(context).apply {
                    text = "Test connection"; textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(pal.onAccent)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = roundRect(pal.accent, dp(14).toFloat())
                    pressable(0.97f) {
                        result.text = "Testing…"
                        Ai.ping(this@SettingsActivity) { result.text = it }
                    }
                }, LinearLayout.LayoutParams(-1, dp(46)))
                addView(result)
            }
            advanced("Models") {
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
            }
        })
    }

    companion object {
        private const val REQ_CONSENT = 41
        const val EXTRA_PAGE = "page"
    }
}
