package com.scratchy.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A sheet for editing one table: a grid of cells, and add / delete for rows and columns. Text in cells is markdown. */
object TableEditor {
    fun show(a: Activity, pal: Palette, data: TableData, onDone: (TableData?) -> Unit) {
        val rows = data.rows.map { it.toMutableList() }.toMutableList()
        var fr = 0; var fc = 0
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val cells = ArrayList<List<EditText>>()

        fun readBack() { cells.forEachIndexed { r, row -> row.forEachIndexed { c, e -> rows[r][c] = e.text.toString() } } }

        lateinit var rebuild: () -> Unit
        rebuild = {
            grid.removeAllViews(); cells.clear()
            rows.forEachIndexed { r, row ->
                val line = LinearLayout(a)
                val list = ArrayList<EditText>()
                row.forEachIndexed { c, text ->
                    val e = EditText(a).apply {
                        setText(text); hint = if (r == 0) "Header" else ""
                        textSize = 15f; setTextColor(pal.text); setHintTextColor(pal.secondary)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        typeface = if (r == 0) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
                        setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                        minimumHeight = a.dp(46)
                        background = roundRect(if (r == 0) (pal.accent and 0xFFFFFF) or 0x26000000 else pal.track, a.dp(10).toFloat())
                        setOnFocusChangeListener { _, has -> if (has) { fr = r; fc = c } }
                    }
                    list += e
                    line.addView(e, LinearLayout.LayoutParams(a.dp(140), -2).apply { setMargins(a.dp(2), a.dp(2), a.dp(2), a.dp(2)) })
                }
                cells += list
                grid.addView(line)
            }
            cells.getOrNull(fr)?.getOrNull(fc)?.requestFocus()
        }

        fun action(label: String, danger: Boolean = false, onTap: () -> Unit) = TextView(a).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (danger) pal.danger else pal.accent)
            setPadding(a.dp(14), 0, a.dp(14), 0)
            background = roundRect((if (danger) pal.danger else pal.accent) and 0xFFFFFF or 0x1F000000, a.dp(18).toFloat())
            pressable(0.95f) { readBack(); onTap(); rebuild() }
        }

        val actions = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(a).apply {
                fun add(v: TextView) = addView(v, LinearLayout.LayoutParams(-2, a.dp(36)).apply { marginEnd = a.dp(8) })
                add(action("＋ Row") { rows.add(fr + 1, MutableList(rows[0].size) { "" }); fr += 1 })
                add(action("＋ Column") { rows.forEach { it.add(fc + 1, "") }; fc += 1 })
                add(action("Delete row", danger = true) { if (rows.size > 1) { rows.removeAt(fr); fr = fr.coerceAtMost(rows.size - 1) } })
                add(action("Delete column", danger = true) { if (rows[0].size > 1) { rows.forEach { it.removeAt(fc) }; fc = fc.coerceAtMost(rows[0].size - 1) } })
            })
        }

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(pal.surface, a.dp(24).toFloat(), (pal.text and 0xFFFFFF) or 0x24000000, a.dp(1))
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(14))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply {
                    text = "Table"; textSize = 20f; setTextColor(pal.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(a).apply {
                    text = "Done"; textSize = 15f; gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(pal.onAccent); setPadding(a.dp(20), 0, a.dp(20), 0)
                    background = roundRect(pal.accent, a.dp(20).toFloat())
                    pressable(0.95f) { readBack(); d.dismiss(); onDone(TableData(rows)) }
                }, LinearLayout.LayoutParams(-2, a.dp(40)))
            })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
            addView(ScrollView(a).apply {
                isVerticalScrollBarEnabled = false
                addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(grid) })
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = a.dp(10) })
            addView(action("Delete table", danger = true) { }.apply {
                pressable(0.95f) { d.dismiss(); onDone(null) }
            }, LinearLayout.LayoutParams(-2, a.dp(36)).apply { topMargin = a.dp(10) })
        }

        val dm = a.resources.displayMetrics
        d.setContentView(card, ViewGroup.LayoutParams(minOf(dm.widthPixels - a.dp(24), a.dp(520)), (dm.heightPixels * 0.78f).toInt()))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.setOnCancelListener { readBack(); onDone(TableData(rows)) }
        rebuild()
        d.show()
    }
}
