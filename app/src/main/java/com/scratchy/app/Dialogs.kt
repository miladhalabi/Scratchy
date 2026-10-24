package com.scratchy.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation

/**
 * The app's own dialogs: a rounded card in the palette colors that springs in, instead of the system dialog.
 * Build with the chained calls, then [show].
 */
class Sheet(private val a: Activity, private val pal: Palette = Palette(a)) {
    private var title = ""
    private var message: String? = null
    private val fields = ArrayList<EditText>()
    private class Item(val label: String, val checked: Boolean, val accent: Boolean, val onTap: () -> Unit)
    private val items = ArrayList<Item>()
    private var confirmLabel: String? = null
    private var danger = false
    private var onConfirm: ((List<String>) -> Unit)? = null
    private var cancelLabel: String? = "Cancel"

    fun title(t: String) = apply { title = t }
    fun message(m: String) = apply { message = m }

    /** A text field; the values come back, in order, to [confirm]. */
    fun field(hint: String, initial: String = "", password: Boolean = false, maxLength: Int = 0) = apply {
        fields += EditText(a).apply {
            this.hint = hint; setText(initial); setSelection(text.length)
            setSingleLine(true); textSize = 16f
            setTextColor(pal.text); setHintTextColor(pal.secondary)
            highlightColor = (pal.accent and 0x00FFFFFF) or 0x55000000
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            if (maxLength > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            background = roundRect(pal.track, a.dp(14).toFloat())
        }
    }

    /** A row in a list. Tapping it closes the dialog, then runs [onTap]. */
    fun item(label: String, checked: Boolean = false, accent: Boolean = false, onTap: () -> Unit) =
        apply { items += Item(label, checked, accent, onTap) }

    fun confirm(label: String, danger: Boolean = false, onOk: (List<String>) -> Unit) = apply {
        confirmLabel = label; this.danger = danger; onConfirm = onOk
    }

    fun cancel(label: String?) = apply { cancelLabel = label }

    fun show(): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window_NO_TITLE)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(androidx.core.graphics.ColorUtils.blendARGB(pal.surface, pal.text, 0.07f), a.dp(28).toFloat(),
                (pal.text and 0x00FFFFFF) or 0x24000000, a.dp(1))
            setPadding(a.dp(22), a.dp(22), a.dp(22), a.dp(8))
            elevation = a.dp(12).toFloat()
        }
        card.addView(TextView(a).apply {
            text = title; textSize = 20f; setTextColor(pal.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            bidi()
        })
        message?.let { m ->
            card.addView(TextView(a).apply {
                text = m; textSize = 15f; setTextColor(pal.secondary); setLineSpacing(0f, 1.1f)
                setPadding(0, a.dp(8), 0, 0)
            })
        }
        fields.forEachIndexed { i, f ->
            card.addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(if (i == 0) 16 else 10) })
        }
        if (items.isNotEmpty()) {
            val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            items.forEach { it ->
                list.addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, a.dp(4), 0)
                    addView(TextView(a).apply {
                        text = it.label; textSize = 16f; maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(if (it.accent) pal.accent else pal.text)
                        if (it.checked) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        bidi()
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (it.checked) addView(TextView(a).apply { text = "✓"; textSize = 16f; setTextColor(pal.accent) })
                    pressable(0.98f) { d.dismiss(); it.onTap() }
                }, LinearLayout.LayoutParams(-1, a.dp(46)))
            }
            val maxH = (a.resources.displayMetrics.heightPixels * 0.5f).toInt()
            card.addView(object : ScrollView(a) {
                override fun onMeasure(w: Int, h: Int) =
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; addView(list) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        }

        val buttons = LinearLayout(a).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        cancelLabel?.let { c ->
            buttons.addView(TextView(a).apply {
                text = c; textSize = 15f; setTextColor(pal.secondary); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(a.dp(16), 0, a.dp(16), 0)
                pressable(0.95f) { d.dismiss() }
            }, LinearLayout.LayoutParams(-2, a.dp(40)))
        }
        confirmLabel?.let { c ->
            buttons.addView(TextView(a).apply {
                text = c; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(if (danger) Color.WHITE else pal.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(a.dp(22), 0, a.dp(22), 0)
                background = roundRect(if (danger) pal.danger else pal.accent, a.dp(22).toFloat())
                pressable(0.95f) { d.dismiss(); onConfirm?.invoke(fields.map { it.text.toString() }) }
            }, LinearLayout.LayoutParams(-2, a.dp(40)).apply { marginStart = a.dp(6) })
        }
        if (buttons.childCount > 0) card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(0) })
        else card.setPadding(a.dp(22), a.dp(22), a.dp(22), a.dp(16))

        val width = minOf(a.resources.displayMetrics.widthPixels - a.dp(48), a.dp(400))
        d.setContentView(FrameLayout(a).apply { addView(card, FrameLayout.LayoutParams(width, -2)) },
            ViewGroup.LayoutParams(width, -2))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setGravity(Gravity.CENTER)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (fields.isNotEmpty()) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0)
            if (a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.show()
        fields.firstOrNull()?.requestFocus()
        if (!Motion.reduced(a)) {
            card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
            Motion.spring(card, DynamicAnimation.ALPHA, 1f, 1f, 0.2f)
            Motion.spring(card, DynamicAnimation.SCALE_X, 1f, 0.8f, 0.3f)
            Motion.spring(card, DynamicAnimation.SCALE_Y, 1f, 0.8f, 0.3f)
        }
        return d
    }

    private companion object { const val Window_NO_TITLE = android.view.Window.FEATURE_NO_TITLE }
}
