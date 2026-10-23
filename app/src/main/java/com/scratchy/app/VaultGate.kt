package com.scratchy.app

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen lock in front of the secret notes. First time: choose a password, then offer the
 * fingerprint. Afterwards: fingerprint (asked automatically) or password.
 */
class VaultGate(
    private val a: Activity,
    private val pal: Palette,
    private val onUnlocked: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(a) {

    private val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(a.dp(28), 0, a.dp(28), 0)
    }

    init {
        setBackgroundColor(pal.background)
        isClickable = true                      // swallow touches so the list underneath is never reachable
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        addView(a.iconButton(R.drawable.ic_back, pal.text, "Back") { onClose() },
            LayoutParams(a.dp(44), a.dp(44), Gravity.TOP or Gravity.START).apply { setMargins(a.dp(8), a.dp(8), 0, 0) })
        if (Vault.isSetUp(a)) showUnlock() else showSetup()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(a).apply {
        this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun field(hint: String) = EditText(a).apply {
        this.hint = hint; textSize = 17f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        setTextColor(pal.text); setHintTextColor(pal.secondary)
        gravity = Gravity.CENTER
        background = roundRect(pal.surface, a.dp(14).toFloat())
        setPadding(a.dp(14), 0, a.dp(14), 0)
    }

    private fun button(text: String, filled: Boolean, onTap: () -> Unit) = TextView(a).apply {
        this.text = text; textSize = 16f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else pal.accent)
        background = if (filled) roundRect(pal.accent, a.dp(14).toFloat()) else null
        pressable(0.97f) { onTap() }
    }

    private fun header(title: String, sub: String) {
        column.removeAllViews()
        column.addView(android.widget.ImageView(a).apply {
            setImageResource(R.drawable.ic_lock); setColorFilter(pal.accent); contentDescription = "Secret notes"
        },
            LinearLayout.LayoutParams(a.dp(64), a.dp(64)))
        column.addView(label(title, 26f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        column.addView(label(sub, 15f, pal.secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8); bottomMargin = a.dp(24) })
    }

    private fun error(msg: String) = label(msg, 14f, 0xFFFF453A.toInt())

    // ---- first time ----

    private fun showSetup(message: String? = null) {
        header("Secret notes", "Choose a password. Notes marked secret are hidden until you unlock them.")
        val pw = field("Password"); val again = field("Repeat password")
        column.addView(pw, LinearLayout.LayoutParams(-1, a.dp(50)))
        column.addView(again, LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(10) })
        val err = error(message ?: "").apply { visibility = if (message == null) View.GONE else View.VISIBLE }
        column.addView(err, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })
        fun submit() {
            val p = pw.text.toString()
            when {
                p.length < 4 -> { err.text = "Use at least 4 characters"; err.visibility = View.VISIBLE }
                p != again.text.toString() -> { err.text = "Passwords don't match"; err.visibility = View.VISIBLE }
                else -> { Vault.setPassword(a, p); afterPassword() }
            }
        }
        again.imeOptions = EditorInfo.IME_ACTION_DONE
        again.setOnEditorActionListener { _, _, _ -> submit(); true }
        column.addView(button("Continue", true) { submit() }, LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(16) })
        pw.requestFocus()
    }

    private fun afterPassword() {
        if (!Vault.biometricAvailable(a)) { Vault.unlock(); onUnlocked(); return }
        header("Use your fingerprint?", "Unlock secret notes with a touch. The password still works as a backup.")
        column.addView(button("Enable fingerprint", true) {
            Vault.askFingerprint(a, onOk = { Vault.setBiometric(a, true); Vault.unlock(); onUnlocked() }, onFail = {})
        }, LinearLayout.LayoutParams(-1, a.dp(50)))
        column.addView(button("Not now", false) { Vault.unlock(); onUnlocked() },
            LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(8) })
    }

    // ---- every other time ----

    private fun showUnlock() {
        header("Secret notes", "Enter your password")
        val pw = field("Password")
        column.addView(pw, LinearLayout.LayoutParams(-1, a.dp(50)))
        val err = error("Wrong password").apply { visibility = View.GONE }
        column.addView(err, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(10) })
        fun submit() {
            if (Vault.checkPassword(a, pw.text.toString())) { Vault.unlock(); onUnlocked() }
            else { err.visibility = View.VISIBLE; pw.text.clear() }
        }
        pw.imeOptions = EditorInfo.IME_ACTION_DONE
        pw.setOnEditorActionListener { _, _, _ -> submit(); true }
        column.addView(button("Unlock", true) { submit() }, LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(16) })
        if (Vault.biometricEnabled(a)) {
            column.addView(button("Use fingerprint", false) { fingerprint() },
                LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(8) })
            post { fingerprint() }
        } else pw.requestFocus()
    }

    private fun fingerprint() = Vault.askFingerprint(a, onOk = { Vault.unlock(); onUnlocked() }, onFail = {})
}
