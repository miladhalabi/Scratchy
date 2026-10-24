package com.scratchy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

enum class FormatKind { BOLD, ITALIC, STRIKE, CODE, HEADING, BULLET, NUMBER, TASK, QUOTE, LINK, OUTDENT, INDENT, TABLE, AI }

/** One button of the formatting bar. Every icon is drawn here with the same stroke and size, so they all match. */
class FormatIcon(ctx: Context, val kind: FormatKind, private val pal: Palette) : View(ctx) {
    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var label = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val path = Path()

    init { contentDescription = kind.name.lowercase().replaceFirstChar { it.uppercase() } }

    private fun text(c: Canvas, s: String, size: Float, style: Int, mono: Boolean = false, strike: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", style)
        paint.textSize = size * d
        paint.isStrikeThruText = strike
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(s, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
        paint.isStrikeThruText = false
    }

    override fun onDraw(c: Canvas) {
        if (active) {
            paint.style = Paint.Style.FILL; paint.color = pal.accent
            rect.set(3 * d, 5 * d, width - 3 * d, height - 5 * d)
            c.drawRoundRect(rect, 10 * d, 10 * d, paint)
        }
        val fg = if (active) pal.onAccent else pal.text
        paint.color = fg
        paint.strokeWidth = 1.9f * d
        val cx = width / 2f
        val cy = height / 2f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) { paint.style = Paint.Style.STROKE; c.drawLine(cx + x1 * d, cy + y1 * d, cx + x2 * d, cy + y2 * d, paint) }
        fun dot(x: Float, y: Float) { paint.style = Paint.Style.FILL; c.drawCircle(cx + x * d, cy + y * d, 1.8f * d, paint) }
        when (kind) {
            FormatKind.BOLD -> text(c, "B", 18f, Typeface.BOLD)
            FormatKind.ITALIC -> text(c, "I", 18f, Typeface.ITALIC)
            FormatKind.STRIKE -> text(c, "S", 18f, Typeface.NORMAL, strike = true)
            FormatKind.CODE -> text(c, "</>", 13f, Typeface.BOLD, mono = true)
            FormatKind.HEADING -> text(c, label.ifEmpty { "H" }, 17f, Typeface.BOLD)
            FormatKind.BULLET -> { for (y in floatArrayOf(-6f, 0f, 6f)) { dot(-8f, y); line(-3f, y, 9f, y) } }
            FormatKind.NUMBER -> {
                for ((i, y) in floatArrayOf(-6f, 0f, 6f).withIndex()) {
                    paint.style = Paint.Style.FILL; paint.textSize = 7.5f * d; paint.textAlign = Paint.Align.CENTER
                    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    c.drawText("${i + 1}", cx - 8f * d, cy + (y + 2.6f) * d, paint)
                    line(-3f, y, 9f, y)
                }
            }
            FormatKind.TASK -> {
                paint.style = Paint.Style.STROKE
                rect.set(cx - 8 * d, cy - 8 * d, cx + 8 * d, cy + 8 * d)
                c.drawRoundRect(rect, 3.5f * d, 3.5f * d, paint)
                path.reset(); path.moveTo(cx - 3.5f * d, cy + 0.3f * d); path.lineTo(cx - 0.8f * d, cy + 3.2f * d); path.lineTo(cx + 4 * d, cy - 3 * d)
                c.drawPath(path, paint)
            }
            FormatKind.QUOTE -> { line(-8f, -7f, -8f, 7f); line(-3f, -4f, 9f, -4f); line(-3f, 2f, 7f, 2f) }
            FormatKind.LINK -> {
                paint.style = Paint.Style.STROKE
                c.save(); c.rotate(-45f, cx, cy)
                rect.set(cx - 10 * d, cy - 4 * d, cx + 2 * d, cy + 4 * d); c.drawRoundRect(rect, 4 * d, 4 * d, paint)
                rect.set(cx - 2 * d, cy - 4 * d, cx + 10 * d, cy + 4 * d); c.drawRoundRect(rect, 4 * d, 4 * d, paint)
                c.restore()
            }
            FormatKind.AI -> {
                paint.style = Paint.Style.FILL
                path.reset()
                val r = 9f * d
                path.moveTo(cx, cy - r); path.quadTo(cx, cy, cx + r, cy); path.quadTo(cx, cy, cx, cy + r)
                path.quadTo(cx, cy, cx - r, cy); path.quadTo(cx, cy, cx, cy - r)
                c.drawPath(path, paint)
                path.reset(); val s = 3.6f * d; val sx = cx + 7.5f * d; val sy = cy - 7.5f * d
                path.moveTo(sx, sy - s); path.quadTo(sx, sy, sx + s, sy); path.quadTo(sx, sy, sx, sy + s)
                path.quadTo(sx, sy, sx - s, sy); path.quadTo(sx, sy, sx, sy - s)
                c.drawPath(path, paint)
            }
            FormatKind.TABLE -> {
                paint.style = Paint.Style.STROKE
                rect.set(cx - 9 * d, cy - 8 * d, cx + 9 * d, cy + 8 * d)
                c.drawRoundRect(rect, 3 * d, 3 * d, paint)
                line(-9f, -2.5f, 9f, -2.5f); line(-9f, 3f, 9f, 3f); line(-3f, -8f, -3f, 8f); line(3f, -8f, 3f, 8f)
            }
            FormatKind.OUTDENT, FormatKind.INDENT -> {
                val dir = if (kind == FormatKind.INDENT) 1f else -1f
                line(-9f, -7f, 9f, -7f); line(-9f, 7f, 9f, 7f); line(1f, 0f, 9f, 0f)
                path.reset()
                path.moveTo(cx + (-3f - dir * 3f) * d, cy - 4 * d); path.lineTo(cx + (-3f + dir * 2f) * d, cy); path.lineTo(cx + (-3f - dir * 3f) * d, cy + 4 * d)
                paint.style = Paint.Style.STROKE; c.drawPath(path, paint)
            }
        }
    }
}
