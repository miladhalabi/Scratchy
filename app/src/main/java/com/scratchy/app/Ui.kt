package com.scratchy.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Every color the app draws with. Built by [Palette]; the fixed sets are plain data so they can be tested. */
data class Colors(
    val accent: Int,
    /** Text and icons placed on top of [accent] (white or near-black, whichever reads better). */
    val onAccent: Int,
    /** The accent as it would look in the opposite light/dark mode: for text on the inverse surface (undo bar). */
    val accentInverse: Int,
    /** A see-through wash of the accent for chips and highlights. */
    val accentContainer: Int,
    val background: Int,
    val surface: Int,
    /** Fully opaque so text behind the floating card never shows through. */
    val floating: Int,
    val text: Int,
    val secondary: Int,
    val hairline: Int,
    /** Background of sliders, switches and segmented controls. */
    val track: Int,
    val danger: Int,
    val success: Int,
)

object Contrast {
    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun luminance(color: Int) =
        0.2126 * lin((color shr 16) and 0xFF) + 0.7152 * lin((color shr 8) and 0xFF) + 0.0722 * lin(color and 0xFF)
    fun ratio(a: Int, b: Int): Double {
        val l1 = luminance(a); val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
    /** White or near-black, whichever stands out more against [bg]. */
    fun onColor(bg: Int): Int = if (ratio(0xFFFFFFFF.toInt(), bg) >= ratio(0xFF101114.toInt(), bg)) 0xFFFFFFFF.toInt() else 0xFF101114.toInt()
}

/** Accent choices for the "Color" setting. The first entry, "wallpaper", follows the phone's Material You colors. */
object Themes {
    val names = listOf("wallpaper", "orange", "blue", "teal", "green", "purple")
    val labels = listOf("Wallpaper", "Orange", "Blue", "Teal", "Green", "Purple")

    /** Accent for a fixed theme: (light mode, dark mode). */
    fun accent(name: String): Pair<Int, Int> = when (name) {
        "orange" -> 0xFFA84C00.toInt() to 0xFFFFAB40.toInt()
        "teal" -> 0xFF00796B.toInt() to 0xFF4DD0C0.toInt()
        "green" -> 0xFF2E7D32.toInt() to 0xFF7FD18B.toInt()
        "purple" -> 0xFF6B4EDD.toInt() to 0xFFB9A6FF.toInt()
        else -> 0xFF1A5FD0.toInt() to 0xFF8AB4FF.toInt()          // blue, also the fallback when there is no wallpaper color
    }

    /** The calm neutral set, with the chosen accent. */
    fun fixed(name: String, dark: Boolean): Colors {
        val (aLight, aDark) = accent(name)
        val acc = if (dark) aDark else aLight
        val inv = if (dark) aLight else aDark
        return Colors(
            accent = acc, onAccent = Contrast.onColor(acc), accentInverse = inv,
            accentContainer = (acc and 0xFFFFFF) or 0x2E000000,
            background = if (dark) 0xFF0F1013.toInt() else 0xFFF2F3F5.toInt(),
            surface = if (dark) 0xFF1C1E22.toInt() else 0xFFFFFFFF.toInt(),
            floating = if (dark) 0xFF25272C.toInt() else 0xFFFFFFFF.toInt(),
            text = if (dark) 0xFFECEEF1.toInt() else 0xFF14161A.toInt(),
            secondary = if (dark) 0xFFA4A9B2.toInt() else 0xFF5B616B.toInt(),
            hairline = if (dark) 0x33FFFFFF else 0x1F000000,
            track = if (dark) 0xFF30333A.toInt() else 0xFFE3E5E9.toInt(),
            danger = if (dark) 0xFFFF6B63.toInt() else 0xFFD93025.toInt(),
            success = if (dark) 0xFF5DD58B.toInt() else 0xFF1E8E3E.toInt(),
        )
    }
}

/** Shared look: colors, springs and press feedback. Follows the wallpaper (Android 12+) unless a fixed color is chosen. */
class Palette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    /** The "Color" setting: one of [Themes.names]. */
    val theme: String = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE).getString("theme_color", "wallpaper") ?: "wallpaper"

    private val c: Colors = if (theme == "wallpaper" && Build.VERSION.SDK_INT >= 31) fromWallpaper(ctx, dark) else
        Themes.fixed(if (theme == "wallpaper") "blue" else theme, dark)

    val accent = c.accent
    val onAccent = c.onAccent
    val accentInverse = c.accentInverse
    val accentContainer = c.accentContainer
    val background = c.background
    val surface = c.surface
    val floating = c.floating
    val text = c.text
    val secondary = c.secondary
    val hairline = c.hairline
    val track = c.track
    val danger = c.danger
    val success = c.success

    companion object {
        /** The phone's wallpaper accent for the given mode, or null before Android 12. */
        fun wallpaperAccent(ctx: Context, dark: Boolean): Int? =
            if (Build.VERSION.SDK_INT >= 31)
                ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600) or 0xFF000000.toInt()
            else null
    }

    /** Changes when the look changes (mode, wallpaper color or setting), so screens know to redraw. */
    val signature = "$theme/$dark/${accent.toString(16)}/${background.toString(16)}"

    @androidx.annotation.RequiresApi(31)
    private fun fromWallpaper(ctx: Context, dark: Boolean): Colors {
        fun col(id: Int) = ctx.getColor(id) or 0xFF000000.toInt()
        val acc = col(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        val inv = col(if (dark) android.R.color.system_accent1_600 else android.R.color.system_accent1_200)
        val fallback = Themes.fixed("blue", dark)
        val surface = col(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_0)
        return Colors(
            accent = acc, onAccent = Contrast.onColor(acc), accentInverse = inv,
            accentContainer = (acc and 0xFFFFFF) or 0x2E000000,
            background = col(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50),
            surface = surface,
            floating = if (dark) col(android.R.color.system_neutral1_800) else col(android.R.color.system_neutral1_10),
            text = col(if (dark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900),
            secondary = col(if (dark) android.R.color.system_neutral2_300 else android.R.color.system_neutral2_700),
            hairline = fallback.hairline,
            track = col(if (dark) android.R.color.system_neutral1_700 else android.R.color.system_neutral1_100),
            danger = fallback.danger, success = fallback.success,
        )
    }
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
