package com.scratchy.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan

/** Draws a real checkbox in place of the ☐ / ☑ marker character, so the stored text stays plain. */
class CheckSpan(
    private val checked: Boolean,
    private val accent: Int,
    private val onAccent: Int,
    private val outline: Int,
    private val density: Float,
) : ReplacementSpan() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val tick = Path()

    override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) =
        (p.textSize * 1.15f).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, p: Paint,
    ) {
        val s = p.textSize * 0.95f
        val cy = y - p.textSize * 0.32f           // visually centred on lowercase letters
        val left = x + p.textSize * 0.04f
        box.set(left, cy - s / 2, left + s, cy + s / 2)
        val r = s * 0.3f
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawRoundRect(box, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.color = onAccent
            paint.strokeWidth = s * 0.14f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            tick.reset()
            tick.moveTo(box.left + s * 0.26f, box.top + s * 0.54f)
            tick.lineTo(box.left + s * 0.43f, box.top + s * 0.70f)
            tick.lineTo(box.left + s * 0.76f, box.top + s * 0.32f)
            canvas.drawPath(tick, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.color = outline
            paint.strokeWidth = 1.7f * density
            val inset = paint.strokeWidth / 2
            box.inset(inset, inset)
            canvas.drawRoundRect(box, r, r, paint)
        }
    }
}

/** Marker spans so they can be found and replaced on every edit. */
class DoneStrike : StrikethroughSpan()
class DoneColor(c: Int) : ForegroundColorSpan(c)

object Checklist {
    const val BOX_OFF = '☐'
    const val BOX_ON = '☑'

    /** (Re)applies checkbox drawing, and strike-through + dimming for ticked lines. */
    fun style(s: Spannable, pal: Palette, density: Float) {
        for (sp in s.getSpans(0, s.length, CheckSpan::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, DoneStrike::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, DoneColor::class.java)) s.removeSpan(sp)
        var i = 0
        while (i < s.length) {
            val nl = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            if (nl > i && (s[i] == BOX_OFF || s[i] == BOX_ON)) {
                val on = s[i] == BOX_ON
                s.setSpan(CheckSpan(on, pal.accent, pal.onAccent, pal.secondary, density), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (on && nl > i + 2) {
                    s.setSpan(DoneStrike(), i + 2, nl, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(DoneColor(pal.secondary), i + 2, nl, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            i = nl + 1
        }
    }
}
