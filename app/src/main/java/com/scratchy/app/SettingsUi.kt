package com.scratchy.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import android.content.res.ColorStateList

/** Building blocks so every Settings row looks and behaves the same. */

fun trackColor(pal: Palette) = pal.track

private fun medium() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** Small capitals label above a group, like "EDGE BAR". */
fun Context.groupHeader(pal: Palette, text: String) = TextView(this).apply {
    this.text = text.uppercase()
    textSize = 12f; setTextColor(pal.secondary); letterSpacing = 0.09f
    typeface = medium()
    setPadding(dp(14), dp(26), 0, dp(8))
}

/** A rounded card holding related rows, separated by hairlines. [plain] makes a transparent group for nesting. */
class GroupCard(ctx: Context, private val pal: Palette, plain: Boolean = false) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        if (!plain) {
            background = roundRect(pal.surface, ctx.dp(22).toFloat())
            setPadding(ctx.dp(18), ctx.dp(4), ctx.dp(18), ctx.dp(4))
        }
    }

    /** Adds a row; build() fills it. A divider is drawn above every row except the first. */
    fun row(build: LinearLayout.() -> Unit): LinearLayout {
        if (childCount > 0) addView(View(context).apply { setBackgroundColor(pal.hairline) }, LayoutParams(-1, 1))
        val r = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, context.dp(16), 0, context.dp(16))
            build()
        }
        addView(r, LayoutParams(-1, -2))
        return r
    }

    /**
     * Rarely used options, folded away behind an "Advanced" line until you tap it.
     * [build] adds rows to the hidden part.
     */
    fun advanced(label: String = "Advanced", build: GroupCard.() -> Unit) {
        val body = GroupCard(context, pal, plain = true).apply(build)
        val arrow = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron); setColorFilter(pal.secondary); rotation = 90f
        }
        row {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(pal.secondary) },
                LayoutParams(0, -2, 1f))
            addView(arrow, LayoutParams(context.dp(22), context.dp(22)))
            pressable(0.99f) {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.rotation = if (open) 270f else 90f
            }
        }
        body.visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(pal.hairline) }, LayoutParams(-1, 1))
        addView(body, LayoutParams(-1, -2))
    }
}

/** One tappable row of the settings menu: tinted icon, title, a one-line summary and an arrow. */
fun LinearLayout.navRow(pal: Palette, icon: Int, title: String, summary: String, onTap: () -> Unit) {
    val c = context
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(android.widget.FrameLayout(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accentContainer) }
        addView(android.widget.ImageView(c).apply { setImageResource(icon); setColorFilter(pal.accent) },
            android.widget.FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
    }, LinearLayout.LayoutParams(c.dp(42), c.dp(42)).apply { marginEnd = c.dp(14) })
    addView(LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(c).apply { text = title; textSize = 16f; setTextColor(pal.text) })
        addView(TextView(c).apply {
            text = summary; textSize = 13f; setTextColor(pal.secondary)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(pal.secondary) },
        LinearLayout.LayoutParams(c.dp(22), c.dp(22)))
    pressable(0.99f) { onTap() }
}

/** The "Color" choice: wallpaper colors, or one fixed accent. */
class ColorSwatches(ctx: Context, private val pal: Palette, selected: String, private val onPick: (String) -> Unit) : LinearLayout(ctx) {
    private val ring = pal.text

    init {
        gravity = Gravity.CENTER_VERTICAL
        Themes.names.forEachIndexed { i, name ->
            val fill = if (name == "wallpaper") (Palette.wallpaperAccent(ctx, pal.dark) ?: Themes.fixed("blue", pal.dark).accent)
            else if (pal.dark) Themes.accent(name).second else Themes.accent(name).first
            addView(android.widget.FrameLayout(ctx).apply {
                contentDescription = Themes.labels[i]
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(fill)
                    if (name == selected) setStroke(ctx.dp(3), ring)
                }
                if (name == "wallpaper") addView(TextView(ctx).apply {
                    text = "✦"; textSize = 15f; setTextColor(Contrast.onColor(fill)); gravity = Gravity.CENTER
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
                pressable(0.9f) { onPick(name) }
            }, LayoutParams(0, ctx.dp(40), 1f).apply { marginStart = ctx.dp(if (i == 0) 0 else 8) })
        }
    }
}

fun LinearLayout.title(pal: Palette, text: String) = addView(TextView(context).apply {
    this.text = text; textSize = 16f; setTextColor(pal.text)
}, LinearLayout.LayoutParams(-1, -2))

fun LinearLayout.caption(pal: Palette, text: String): TextView {
    val t = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(pal.secondary); setLineSpacing(0f, 1.1f)
    }
    addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
    return t
}

/** A row of choices with an accent highlight that slides to the one you pick. */
class SegmentedControl(
    ctx: Context,
    private val pal: Palette,
    labels: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : FrameLayout(ctx) {
    private val n = labels.size
    private var index = selected
    private val thumb = View(ctx)
    private val cells = labels.map { TextView(ctx) }
    private val pad = ctx.dp(3)
    private val cellH = ctx.dp(34)
    private var cellW = 0

    init {
        background = roundRect(trackColor(pal), ctx.dp(12).toFloat())
        setPadding(pad, pad, pad, pad)
        thumb.background = roundRect(pal.accent, ctx.dp(10).toFloat())
        addView(thumb, LayoutParams(0, cellH))
        val row = LinearLayout(ctx)
        cells.forEachIndexed { i, tv ->
            tv.apply {
                text = labels[i]; gravity = Gravity.CENTER; textSize = 14f; typeface = medium()
                maxLines = 1
                setOnClickListener { choose(i) }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, cellH, 1f))
        }
        addView(row, LayoutParams(-1, cellH))
        paintText()
    }

    private fun paintText() = cells.forEachIndexed { i, tv -> tv.setTextColor(if (i == index) pal.onAccent else pal.text) }

    // The highlight is as wide as one option; its width must be set before children are measured.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        if (w > 0) {
            val cw = (w - 2 * pad) / n
            if (cw != cellW) {
                cellW = cw
                thumb.layoutParams = LayoutParams(cw, cellH)
                thumb.translationX = (index * cw).toFloat()
            }
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    /** Moves the highlight without calling back (used to reflect state set elsewhere). */
    fun set(i: Int) {
        if (i == index) return
        index = i
        paintText()
        Motion.spring(thumb, DynamicAnimation.TRANSLATION_X, (i * cellW).toFloat(), 0.8f, 0.3f)
    }

    private fun choose(i: Int) {
        if (i == index) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        set(i)
        onSelect(i)
    }
}

/** A labelled slider with its current value on the right. */
class SliderRow(
    ctx: Context,
    private val pal: Palette,
    titleText: String,
    private val minValue: Int,
    maxValue: Int,
    value: Int,
    private val format: (Int) -> String,
    private val onChange: (Int) -> Unit,
) : LinearLayout(ctx) {
    private val valueView = TextView(ctx)
    private val bar = SeekBar(ctx)

    init {
        orientation = VERTICAL
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = titleText; textSize = 16f; setTextColor(pal.text) },
                LayoutParams(0, -2, 1f))
            addView(valueView.apply { textSize = 15f; setTextColor(pal.accent); typeface = medium() })
        })

        // A thin rounded track with an accent fill, centred in the bar (the default one is nearly
        // invisible on a dark card, or very thick).
        val barH = ctx.dp(34)
        val trackH = ctx.dp(4)
        val track = android.graphics.drawable.GradientDrawable().apply {
            setColor(pal.hairline); cornerRadius = trackH / 2f
        }
        val fill = android.graphics.drawable.ClipDrawable(
            android.graphics.drawable.GradientDrawable().apply { setColor(pal.accent); cornerRadius = trackH / 2f },
            Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            // fixed-height layers centred vertically (more reliable than insets for a progress drawable)
            for (i in 0..1) { setLayerGravity(i, Gravity.CENTER_VERTICAL); setLayerHeight(i, trackH) }
        }

        bar.progressDrawable = layers
        bar.thumbTintList = ColorStateList.valueOf(pal.accent)
        bar.splitTrack = false
        bar.max = maxValue - minValue
        bar.progress = (value - minValue).coerceIn(0, maxValue - minValue)
        bar.setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                valueView.text = format(p + minValue)
                if (fromUser) onChange(p + minValue)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        valueView.text = format(value)
        addView(bar, LayoutParams(-1, barH).apply { topMargin = ctx.dp(4) })
    }

    fun set(v: Int) { bar.progress = v - minValue; valueView.text = format(v) }

    /** Greys the slider out and ignores touches (for settings that don't apply right now). */
    fun enabledLook(on: Boolean) { bar.isEnabled = on; alpha = if (on) 1f else 0.4f }
}
