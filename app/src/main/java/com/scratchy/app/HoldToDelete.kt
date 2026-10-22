package com.scratchy.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.hypot

/** Vibration helpers: a pulse with chosen strength, a confirming thump, and cancel. */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)

    /**
     * One crisp haptic click for charge progress [p] (0..1). Uses the motor's own primitives when
     * it has them: a light tick early, a full click as it fills, then a double click near the end.
     */
    fun pulse(ctx: Context, p: Float) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 30 &&
            v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            val c = VibrationEffect.startComposition()
            when {
                p < 0.35f -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f + p)
                p < 0.8f -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f + 0.6f * p)
                else -> {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f, 28)
                }
            }
            v.vibrate(c.compose())
        } else if (Build.VERSION.SDK_INT >= 29) {
            // This phone's motor has built-in effects: tick -> click -> heavy click as the hold fills.
            v.vibrate(VibrationEffect.createPredefined(
                when {
                    p < 0.35f -> VibrationEffect.EFFECT_TICK
                    p < 0.75f -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }))
        } else {
            v.vibrate(VibrationEffect.createOneShot(12, (60 + 190 * p).toInt().coerceIn(1, 255)))
        }
    }

    /**
     * The final confirmation, made as heavy as the motor allows: two heavy clicks, then a
     * full-strength buzz, each landing just after the previous one so they stack into one hard hit.
     */
    fun thump(ctx: Context) {
        val v = vibrator(ctx) ?: return
        val h = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            h.postDelayed({
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            }, 60)
            h.postDelayed({
                v.vibrate(VibrationEffect.createOneShot(90, 255))
            }, 130)
        } else v.vibrate(VibrationEffect.createOneShot(140, 255))
    }

    fun cancel(ctx: Context) {
        vibrator(ctx)?.cancel()
    }
}

/**
 * Tap opens; press and hold deletes. After a short hold the view charges red while vibrating in
 * pulses that get faster and stronger. Reaching the end deletes (the view slides away first).
 * Letting go or moving the finger away before that cancels.
 */
fun View.tapOrHoldToDelete(radiusPx: Float, onTap: () -> Unit, onDelete: () -> Unit) {
    val ctx = context
    val main = Handler(Looper.getMainLooper())
    val red = GradientDrawable().apply { setColor(0xFFFF3B30.toInt()); cornerRadius = radiusPx; alpha = 0 }
    foreground = red

    val holdDelay = 380L      // before charging starts
    val chargeMs = 1000f      // time to fill
    val slop = (10 * resources.displayMetrics.density)
    val cancelDist = (28 * resources.displayMetrics.density)

    var downX = 0f; var downY = 0f
    var charging = false; var done = false; var moved = false
    var chargeStart = 0L

    fun settleBack() {
        Motion.spring(this, DynamicAnimation.SCALE_X, 1f, 0.7f, 0.3f)
        Motion.spring(this, DynamicAnimation.SCALE_Y, 1f, 0.7f, 0.3f)
    }
    fun fadeRed() {
        android.animation.ValueAnimator.ofInt(red.alpha, 0).apply {
            duration = 180
            addUpdateListener { red.alpha = it.animatedValue as Int; invalidate() }
            start()
        }
    }
    fun cancelCharge() {
        main.removeCallbacksAndMessages(null)
        if (charging) { charging = false; Haptics.cancel(ctx); fadeRed() }
        settleBack()
    }

    lateinit var tick: Runnable
    tick = Runnable {
        if (!charging) return@Runnable
        val p = ((SystemClock.uptimeMillis() - chargeStart) / chargeMs).coerceIn(0f, 1f)
        red.alpha = (p * 0.9f * 255).toInt(); invalidate()
        if (p >= 1f) {
            charging = false; done = true
            Haptics.thump(ctx)
            Motion.spring(this, DynamicAnimation.TRANSLATION_X, width * 1.15f, 1f, 0.35f)
            Motion.spring(this, DynamicAnimation.ALPHA, 0f, 1f, 0.3f) { onDelete() }
            return@Runnable
        }
        // Pulse: stronger and quicker as it fills, with a small squeeze you can see.
        Haptics.pulse(ctx, p)
        scaleX = 0.955f; scaleY = 0.955f
        Motion.spring(this, DynamicAnimation.SCALE_X, 0.975f, 1f, 0.12f)
        Motion.spring(this, DynamicAnimation.SCALE_Y, 0.975f, 1f, 0.12f)
        main.postDelayed(tick, (190 - 130 * p).toLong())
    }
    val startCharge = Runnable {
        charging = true
        chargeStart = SystemClock.uptimeMillis()
        parent?.requestDisallowInterceptTouchEvent(true)   // keep the list from scrolling away
        main.post(tick)
    }

    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false; done = false
                Motion.spring(v, DynamicAnimation.SCALE_X, 0.98f, 1f, 0.15f)
                Motion.spring(v, DynamicAnimation.SCALE_Y, 0.98f, 1f, 0.15f)
                main.postDelayed(startCharge, holdDelay)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(e.rawX - downX, e.rawY - downY)
                if (!charging && d > slop && !moved) { moved = true; main.removeCallbacks(startCharge); settleBack() }
                if (charging && d > cancelDist) { moved = true; cancelCharge() }
            }
            MotionEvent.ACTION_UP -> {
                val wasCharging = charging
                val tap = !moved && !charging && !done
                cancelCharge()
                if (tap) onTap()
                else if (wasCharging) Unit
            }
            MotionEvent.ACTION_CANCEL -> cancelCharge()
        }
        true
    }
}
