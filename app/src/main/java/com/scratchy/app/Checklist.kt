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
        drawCheckbox(canvas, paint, box, tick, x + p.textSize * 0.04f, y - p.textSize * 0.32f, s, checked, accent, onAccent, outline, density)
    }
}

/** A rounded checkbox with its left edge at [left] and its centre at [cy]. Shared by the editor and the read-only views. */
fun drawCheckbox(
    canvas: Canvas, paint: Paint, box: RectF, tick: Path, left: Float, cy: Float, s: Float,
    checked: Boolean, accent: Int, onAccent: Int, outline: Int, density: Float,
) {
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

/** Marker spans so they can be found and replaced on every edit. */
class DoneStrike : StrikethroughSpan()
class DoneColor(c: Int) : ForegroundColorSpan(c)
