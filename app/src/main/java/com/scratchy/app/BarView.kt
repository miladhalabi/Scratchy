package com.scratchy.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.cos
import kotlin.math.sin

/**
 * The edge bar: a plain slim pill that sits a little way in from the screen edge (so a screen
 * protector doesn't cover it). It fills the touch zone and is drawn by hand so it can animate:
 *  - thickens when pressed
 *  - takes a slow breath now and then, and fades back when tucked away
 *  - sends out a few small dots when a screenshot is waiting
 */
class BarView(ctx: Context) : View(ctx) {

    var onRight = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private var tintColor = Color.parseColor("#FF8A00")

    /** Distance from the screen edge to the pill, in px. Set from Settings. */
    var gapPx = 8f * d
        set(v) { field = v; invalidate() }

    /** Distance from the screen edge to the middle of the resting pill, for the card animation's target. */
    val sliverCenterPx get() = gapPx + 3f * d

    // animated state (each is driven by a spring or an animator)
    private var press = 0f          // 0 rest .. 1 pressed (thicker)
    private var tuck = 0f           // 0 normal .. 1 idle (thinner and fainter)
    private var level = 0.9f        // overall opacity
    private var lengthScale = 1f
    private var breath = 0f         // 0..1 one slow breath: slightly thicker and brighter
    private var dots = 0f           // 0 none, 0..1 dots spreading

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Path()
    private val rect = RectF()

    private fun prop(name: String, get: BarView.() -> Float, set: BarView.(Float) -> Unit) =
        object : FloatPropertyCompat<BarView>(name) {
            override fun getValue(v: BarView) = v.get()
            override fun setValue(v: BarView, value: Float) { v.set(value); v.invalidate() }
        }
    private val pressP = prop("press", { press }, { press = it })
    private val tuckP = prop("tuck", { tuck }, { tuck = it })
    private val levelP = prop("level", { level }, { level = it })
    private val lenP = prop("len", { lengthScale }, { lengthScale = it })
    private val running = HashMap<String, SpringAnimation>()

    private fun spring(p: FloatPropertyCompat<BarView>, to: Float, damping: Float = 1f, response: Float = 0.3f) {
        if (Motion.reduced(context)) { p.setValue(this, to); return }
        running[p.toString()]?.cancel()
        val a = SpringAnimation(this, p).apply {
            spring = SpringForce(to).setDampingRatio(damping).setStiffness(Motion.stiffness(response))
            minimumVisibleChange = 1f / 400f
        }
        running[p.toString()] = a
        a.start()
    }

    // ---- animation hooks ----

    /** Finger down: the pill thickens and brightens straight away. */
    fun pressDown() { wake(); spring(pressP, 1f, 1f, 0.15f) }

    /** Finger up: springs back with a little wobble. */
    fun pressUp() {
        spring(pressP, 0f, 0.55f, 0.32f)
        spring(lenP, 1f, 0.7f, 0.3f)
    }

    fun longPressed() { spring(lenP, 1.14f, 0.8f, 0.3f); spring(pressP, 0.6f, 0.7f, 0.25f) }

    /** Full visibility: back to normal thickness and brightness. */
    fun wake() { spring(tuckP, 0f, 0.8f, 0.3f); spring(levelP, 1f, 1f, 0.2f) }

    /** Idle: thinner and fainter, so it stays out of the way without disappearing. */
    fun tuckAway(partial: Boolean = false) {
        spring(tuckP, if (partial) 0f else 1f, 0.9f, 0.5f)
        spring(levelP, if (partial) 0.95f else 0.6f, 1f, 0.5f)
    }

    /** A quick swell, like swallowing something. */
    fun swell() { press = 1.5f; invalidate(); spring(pressP, 0f, 0.4f, 0.45f) }

    /** Landing after a drag: squashes thick, then settles. */
    fun landing() { press = 1.25f; invalidate(); spring(pressP, 0f, 0.45f, 0.38f) }

    /** One slow breath. [strong] is quicker and a little bigger (used while a screenshot waits). */
    fun breatheOnce(strong: Boolean) {
        if (Motion.reduced(context)) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (strong) 1100L else 2400L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breath = sin(Math.PI * (it.animatedValue as Float)).toFloat() * (if (strong) 1f else 0.7f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { breath = 0f; invalidate() }
            })
            start()
        }
    }

    /** A few small dots spreading out from the bar. */
    fun dotsOnce() {
        if (Motion.reduced(context)) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            addUpdateListener { dots = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { dots = 0f; invalidate() }
            })
            start()
        }
    }

    fun setTint(color: Int, animate: Boolean = true) {
        if (!animate || Motion.reduced(context)) { tintColor = color; invalidate(); return }
        ValueAnimator.ofObject(ArgbEvaluator(), tintColor, color).apply {
            duration = 280L
            addUpdateListener { tintColor = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dir = if (onRight) -1f else 1f
        val edge = if (onRight) w else 0f
        val cy = h / 2f
        val t = d * (6f + 4.5f * press.coerceIn(0f, 1.6f) + 1.5f * breath) * (1f - 0.25f * tuck)
        val len = d * 56f * lengthScale
        // the pill starts [gapPx] in from the screen edge and grows inward
        val l: Float; val r: Float
        if (!onRight) { l = edge + gapPx; r = l + t } else { r = edge - gapPx; l = r - t }
        val top = cy - len / 2f; val bottom = cy + len / 2f

        pill.reset(); rect.set(l, top, r, bottom)
        pill.addRoundRect(rect, t / 2f, t / 2f, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = tintColor
        paint.alpha = (255 * (level + 0.1f * breath).coerceIn(0f, 1f)).toInt()
        c.drawPath(pill, paint)

        // Three arcs of small dots leave the bar one after another, each easing outward and fading,
        // like a soft sonar. Even spacing and a stagger keep it tidy instead of a burst.
        if (dots > 0f && dots < 1f) {
            val cx = if (!onRight) r else l
            val reachMax = d * 24f
            for (arc in 0 until 3) {
                val p = ((dots - arc * 0.17f) / 0.66f).coerceIn(0f, 1f)
                if (p <= 0f || p >= 1f) continue
                val eased = 1f - (1f - p) * (1f - p) * (1f - p)               // ease-out: quick start, soft landing
                val fade = (1f - p) * (1f - p)                                // fades out smoothly
                val dist = eased * reachMax + d * 3f
                paint.color = tintColor
                paint.alpha = (255 * 0.85f * fade).toInt()
                for (i in -2..2) {
                    val ang = Math.toRadians((i * 24).toDouble())
                    val x = cx + dir * (cos(ang).toFloat() * dist)
                    val y = cy + sin(ang).toFloat() * dist * 1.9f
                    c.drawCircle(x, y, d * (2.1f - 0.7f * p) * (1f - 0.12f * kotlin.math.abs(i)), paint)
                }
            }
        }
    }
}
