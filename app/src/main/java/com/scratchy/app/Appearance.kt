package com.scratchy.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.WindowManager

/** How the floating card looks: solid or glass, how see-through, how much it blurs what is behind it. */
object Appearance {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun isGlass(ctx: Context) = prefs(ctx).getString("style", "solid") == "glass"
    fun setStyle(ctx: Context, glass: Boolean) =
        prefs(ctx).edit().putString("style", if (glass) "glass" else "solid").apply()

    /** 30..100 (%). Glass starts see-through unless you set it yourself. */
    fun opacity(ctx: Context) = prefs(ctx).getInt("opacity", if (isGlass(ctx)) 60 else 100).coerceIn(30, 100)
    fun setOpacity(ctx: Context, v: Int) = prefs(ctx).edit().putInt("opacity", v).apply()

    /** 0..100. Off by default: it blurs the whole screen behind the card unless [blurUnderCard] is on. */
    fun blur(ctx: Context) = prefs(ctx).getInt("blur", 0).coerceIn(0, 100)
    fun setBlur(ctx: Context, v: Int) = prefs(ctx).edit().putInt("blur", v).apply()

    /** Blur only the area under the card, instead of the whole screen behind it. */
    fun blurUnderCard(ctx: Context) = prefs(ctx).getString("blur_area", "screen") == "card"
    fun setBlurUnderCard(ctx: Context, on: Boolean) = prefs(ctx).edit().putString("blur_area", if (on) "card" else "screen").apply()

    fun blurSupported(ctx: Context) =
        Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    /** Blur radius in pixels for the window, or 0 when off or unsupported. */
    fun blurRadiusPx(ctx: Context): Int {
        val v = blur(ctx)
        return if (v > 0 && blurSupported(ctx)) (v * 0.7f * ctx.resources.displayMetrics.density).toInt() else 0
    }

    fun cardRadius(ctx: Context) = ctx.dp(if (isGlass(ctx)) 26 else 20).toFloat()

    fun card(ctx: Context, pal: Palette): Drawable {
        val opacity = opacity(ctx)
        val a = (255 * opacity / 100f).toInt()
        val base = if (isGlass(ctx)) (pal.surface and 0xFFFFFF) else (pal.floating and 0xFFFFFF)
        val fill = (a shl 24) or (base and 0xFFFFFF)
        return if (isGlass(ctx)) GlassDrawable(fill, cardRadius(ctx), ctx.resources.displayMetrics.density, pal.dark)
        else roundRect(fill, cardRadius(ctx), pal.hairline, 1)
    }
}

/**
 * Frosted glass with a light-catching edge: tinted fill, a soft sheen across the top, a darker
 * lower edge for depth, and a thin rim that is bright at the top-left and bottom-right and fades
 * between. (Apple's version also refracts what is behind it; an app can't read that.)
 */
class GlassDrawable(
    private val fill: Int,
    private val radius: Float,
    private val density: Float,
    private val dark: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        // glass body
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = fill
        c.drawRoundRect(rect, radius, radius, paint)

        // sheen: brighter near the top, gone by about 55% of the way down
        paint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
            intArrayOf(Color.argb(if (dark) 46 else 120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)

        // depth: slightly darker toward the bottom edge
        paint.shader = LinearGradient(
            0f, rect.bottom - rect.height() * 0.35f, 0f, rect.bottom,
            Color.argb(0, 0, 0, 0), Color.argb(if (dark) 70 else 28, 0, 0, 0), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)

        // outer rim: bright top-left and bottom-right, soft in between
        val rim = density * 1.4f
        val rimRect = RectF(rect).apply { inset(rim / 2, rim / 2) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = rim
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(
                Color.argb(if (dark) 200 else 235, 255, 255, 255),
                Color.argb(if (dark) 34 else 70, 255, 255, 255),
                Color.argb(if (dark) 34 else 70, 255, 255, 255),
                Color.argb(if (dark) 130 else 190, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.32f, 0.68f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rimRect, radius - rim / 2, radius - rim / 2, paint)

        // faint inner edge, like the thickness of the glass
        val inner = RectF(rect).apply { inset(rim * 1.6f, rim * 1.6f) }
        paint.shader = null; paint.strokeWidth = density * 0.8f
        paint.color = Color.argb(if (dark) 26 else 40, 255, 255, 255)
        c.drawRoundRect(inner, radius - rim * 1.6f, radius - rim * 1.6f, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
