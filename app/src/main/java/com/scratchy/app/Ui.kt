package com.scratchy.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Shared look: colors, springs and press feedback. */
class Palette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val accent = Color.parseColor(if (dark) "#FF9F0A" else "#FF8A00")
    val background = Color.parseColor(if (dark) "#000000" else "#F2F2F7")
    val surface = Color.parseColor(if (dark) "#1C1C1E" else "#FFFFFF")
    /** Fully opaque so text behind the card never shows through; the shadow and hairline separate it. */
    val floating = Color.parseColor(if (dark) "#FF2A2A2D" else "#FFFFFFFF")
    val text = Color.parseColor(if (dark) "#FFFFFF" else "#000000")
    val secondary = Color.parseColor(if (dark) "#99FFFFFF" else "#8A8A8E")
    val hairline = Color.parseColor(if (dark) "#33FFFFFF" else "#1F000000")
}

object Motion {
    /** Apple "response" (seconds) -> spring stiffness for mass 1. */
    fun stiffness(response: Float) = Math.pow(2 * Math.PI / response, 2.0).toFloat()

    /** Honors the system "remove animations" setting. */
    fun reduced(ctx: Context) =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun spring(
        view: View,
        prop: DynamicAnimation.ViewProperty,
        to: Float,
        damping: Float = 1f,
        response: Float = 0.35f,
        velocity: Float = 0f,
        onEnd: (() -> Unit)? = null,
    ): SpringAnimation {
        val a = SpringAnimation(view, prop).apply {
            spring = SpringForce(to).setDampingRatio(damping).setStiffness(stiffness(response))
            setStartVelocity(velocity)
            minimumVisibleChange = if (prop == DynamicAnimation.ALPHA) 1f / 256f else
                if (prop == DynamicAnimation.SCALE_X || prop == DynamicAnimation.SCALE_Y) 1f / 500f else 1f
        }
        onEnd?.let { cb -> a.addEndListener { _, _, _, _ -> cb() } }
        a.start()
        return a
    }

    /** Apple's momentum projection: where a flick with this velocity (px/s) would come to rest. */
    fun project(velocity: Float, rate: Float = 0.998f) = (velocity / 1000f) * rate / (1 - rate)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun roundRect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Responds on pointer-down (instant), springs back on release; click fires on release inside. */
fun View.pressable(scaleDown: Float = 0.96f, onClick: () -> Unit) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Motion.spring(v, DynamicAnimation.SCALE_X, scaleDown, 1f, 0.15f)
                .also { Motion.spring(v, DynamicAnimation.SCALE_Y, scaleDown, 1f, 0.15f) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Motion.spring(v, DynamicAnimation.SCALE_X, 1f, 0.7f, 0.3f)
                Motion.spring(v, DynamicAnimation.SCALE_Y, 1f, 0.7f, 0.3f)
                if (e.actionMasked == MotionEvent.ACTION_UP &&
                    e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()
                ) onClick()
            }
        }
        true
    }
}

fun Context.iconButton(res: Int, tint: Int, desc: String, onClick: () -> Unit) =
    android.widget.ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        val p = dp(9)
        setPadding(p, p, p, p)
        contentDescription = desc
        pressable(0.85f, onClick)
    }

/** Aligns and orders text by its own first strong character, so Arabic/Hebrew lines run right-to-left. */
fun TextView.bidi() = apply {
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    textAlignment = View.TEXT_ALIGNMENT_TEXT_START
}
