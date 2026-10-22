package com.scratchy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * macOS-style "genie": draws a snapshot of the card warped on a mesh so it funnels into a point
 * (the edge bar). [t] runs from 0 (card as it was) to 1 (collapsed into the target).
 *
 * Each column of the snapshot has its own progress: columns nearest the target are pulled in first,
 * so the card narrows into a neck while its far side is still wide, then everything follows.
 */
class GenieView(
    ctx: Context,
    private val bmp: Bitmap,
    private val originScreenX: Float,     // where the snapshot sits on screen
    private val originScreenY: Float,
    private val targetScreenX: Float,     // where it is sucked into
    private val targetScreenY: Float,
    private val towardRight: Boolean,
    initialT: Float,
) : View(ctx) {

    var t: Float = initialT
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** 0..1 multiplier on the snapshot's opacity, used to crossfade with the live card. */
    var fade: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val cols = 36
    private val rows = 8
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val loc = IntArray(2)

    private fun smooth(q: Float) = q * q * (3 - 2 * q)

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        val ox = originScreenX - loc[0]
        val oy = originScreenY - loc[1]
        val tx = targetScreenX - loc[0] - ox        // target in snapshot coordinates
        val ty = targetScreenY - loc[1] - oy
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        for (j in 0..rows) {
            val y0 = h * j / rows
            for (i in 0..cols) {
                val u = i.toFloat() / cols
                val x0 = w * u
                val near = if (towardRight) u else 1f - u            // 1 = closest to the target
                val q = (t * 1.7f - (1f - near) * 0.7f).coerceIn(0f, 1f)
                val shrink = smooth(q)                               // funnel: squeeze toward the target's height
                val slide = q * q                                    // then slide in toward it
                val k = (j * (cols + 1) + i) * 2
                verts[k] = x0 + (tx - x0) * slide
                verts[k + 1] = y0 + (ty - y0) * shrink
            }
        }
        // Fade out over the last stretch so it dissolves into the bar instead of just stopping.
        paint.alpha = (255 * fade * (1f - smooth(((t - 0.85f) / 0.15f).coerceIn(0f, 1f)))).toInt()
        canvas.save()
        canvas.translate(ox, oy)
        canvas.drawBitmapMesh(bmp, cols, rows, verts, 0, null, 0, paint)
        canvas.restore()
    }

    companion object {
        /**
         * How much of the snapshot to show while the live card is [live] visible (0..1) so that the two
         * stacked translucent copies always add up to exactly one card of opacity [cardAlpha]:
         * (1 - a*s) * (1 - a*live) = 1 - a. Without this the overlap looks brighter for a moment.
         */
        fun snapshotShare(live: Float, cardAlpha: Float): Float {
            // A plain linear fade is wrong even for a solid card: two half-faded opaque layers cover
            // only 75% of what is behind, so the screen shows through for a moment. This curve keeps
            // the covered share equal to the card's own opacity at every step. (Capped just below 1
            // so a fully opaque card still hands over smoothly instead of dividing by zero.)
            val a = cardAlpha.coerceIn(0.05f, 0.995f)
            return ((1f - (1f - a) / (1f - a * live)) / a).coerceIn(0f, 1f)
        }
    }
}
