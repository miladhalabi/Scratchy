package com.scratchy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * A thin bar on the screen edge, drawn over all apps.
 *  - tap it, or swipe inward  -> a side panel slides out with a full note editor
 *  - drag up/down             -> reposition along the edge
 *  - long-press, then drag    -> move to the other side
 * The app underneath (e.g. YouTube) is never paused: this is a window, not an Activity.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var ui: Context
    private lateinit var pal: Palette
    private lateinit var bar: BarView                 // the visible glass pill
    private lateinit var zone: FrameLayout            // the (wider) touch target window
    private lateinit var zoneParams: WindowManager.LayoutParams
    private var panel: PanelLayout? = null
    private var editor: NoteEditor? = null
    private var panelOpen = false
    private var cardPinned = false                 // pinned: stays on screen, outside taps pass through
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelFocusable = true
    private var onRight = false
    private val main = Handler(Looper.getMainLooper())
    private val tuckRunnable = Runnable { tuck() }

    private var pendingShot: Uri? = null              // a fresh screenshot waiting to be attached
    private var lastShotId = -1L
    private val clearPending = Runnable { clearPendingShot() }
    private var chooserOpen = false                   // the card is asking where a screenshot goes
    /** A waiting screenshot is forgotten after this long (Settings, default 60 s). */
    private fun forgetAfterMs() = overlayPrefs.getInt("shot_ttl", 60).coerceIn(5, 3600) * 1000L
    private val expirePending = object : Runnable {
        override fun run() {
            // Never pull the question away while you are looking at it; try again shortly.
            if (chooserOpen) main.postDelayed(this, 10_000) else clearPendingShot()
        }
    }
    private lateinit var overlayPrefs: android.content.SharedPreferences
    private val sideListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "right" && !panelOpen) applySideFromPrefs()
        if (key == "bar_gap" && ::bar.isInitialized) bar.gapPx = barGapPx()
    }
    private val shotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // The file may still be written when the gallery notifies; look a moment later.
            main.postDelayed({ checkForScreenshot() }, 700)
        }
    }

    private val zoneW get() = dp(40)
    private val zoneH get() = dp(88)
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels
    private val reduced get() = Motion.reduced(this)
    private val minY get() = dp(40)
    private val maxY get() = screenH - zoneH - dp(80)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ui = ContextThemeWrapper(this, android.R.style.Theme_Material_Light)
        pal = Palette(this)
        startAsForeground()
        createBar()
        lastShotId = Screenshots.latest(this)?.id ?: -1L
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, shotObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::overlayPrefs.isInitialized) overlayPrefs.unregisterOnSharedPreferenceChangeListener(sideListener)
        removeDismissCatcher()
        genieViews.forEach { runCatching { wm.removeView(it) } }
        contentResolver.unregisterContentObserver(shotObserver)
        main.removeCallbacks(tuckRunnable)
        main.removeCallbacks(breathRunnable)
        main.removeCallbacks(pendingPulse)
        main.removeCallbacks(expirePending)
        main.removeCallbacks(clearPending)
        editor?.commit()
        panel?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.removeView(zone) }
        running = false
        super.onDestroy()
    }

    // ---- foreground notification (also the fallback entry point later) ----

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scratchy overlay", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pen)
            .setContentTitle("Scratchy is ready")
            .setContentText("Tap the bar on the screen edge to write a note")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)
        running = true
    }

    // ---- edge bar ----

    private val xProp = object : FloatPropertyCompat<WindowManager.LayoutParams>("x") {
        override fun getValue(p: WindowManager.LayoutParams) = p.x.toFloat()
        override fun setValue(p: WindowManager.LayoutParams, v: Float) { p.x = v.toInt(); updateZone() }
    }
    private val yProp = object : FloatPropertyCompat<WindowManager.LayoutParams>("y") {
        override fun getValue(p: WindowManager.LayoutParams) = p.y.toFloat()
        override fun setValue(p: WindowManager.LayoutParams, v: Float) { p.y = v.toInt(); updateZone() }
    }
    private var xAnim: SpringAnimation? = null
    private var yAnim: SpringAnimation? = null

    private fun updateZone() {
        if (zone.isAttachedToWindow) wm.updateViewLayout(zone, zoneParams)
    }

    private fun restX(right: Boolean) = if (right) screenW - zoneW else 0

    private fun placeBar() {
        bar.onRight = onRight
    }

    /** How far in from the screen edge the bar sits (Settings, default 8dp), so a screen protector doesn't cover it. */
    private fun barGapPx() = dp(overlayPrefs.getInt("bar_gap", 8).coerceIn(0, 24)).toFloat()

    private fun createBar() {
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        overlayPrefs = prefs
        prefs.registerOnSharedPreferenceChangeListener(sideListener)
        onRight = prefs.getBoolean("right", false)
        bar = BarView(ui).apply {
            gapPx = barGapPx()
            setTint(pal.accent, animate = false)
            contentDescription = "Scratchy: open notes"
        }
        zone = FrameLayout(ui).apply { addView(bar, FrameLayout.LayoutParams(-1, -1)) }
        placeBar()
        zoneParams = WindowManager.LayoutParams(
            zoneW, zoneH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = restX(onRight)
            y = prefs.getInt("y", dp(260)).coerceIn(minY, maxY)
        }

        // mode: 0 undecided, 1 vertical drag, 2 free move (after long-press), 3 swiped open
        var mode = 0
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var tracker: VelocityTracker? = null
        val slop = dp(8)
        val longPress = Runnable {
            if (mode == 0) {
                mode = 2
                zone.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                bar.longPressed()
            }
        }

        zone.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(tuckRunnable)
                    xAnim?.cancel(); yAnim?.cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = zoneParams.x; startY = zoneParams.y
                    mode = 0
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    // Respond on touch-down: the pill thickens and brightens immediately.
                    bar.pressDown()
                    main.postDelayed(longPress, 380)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (mode == 0) {
                        if (abs(dx) > slop || abs(dy) > slop) main.removeCallbacks(longPress)
                        val inward = if (onRight) -dx else dx
                        if (inward > dp(22) && abs(dx) > abs(dy)) {
                            mode = 3
                            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            toggleCard()
                        } else if (abs(dy) > slop && abs(dy) > abs(dx)) mode = 1
                    }
                    when (mode) {   // 1:1 with the finger
                        1 -> { zoneParams.y = (startY + dy).toInt().coerceIn(minY, maxY); updateZone() }
                        2 -> {
                            zoneParams.x = startX + dx.toInt()
                            zoneParams.y = (startY + dy).toInt().coerceIn(minY, maxY)
                            updateZone()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    bar.pressUp()
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    when {
                        mode == 3 -> {}
                        e.actionMasked == MotionEvent.ACTION_CANCEL -> scheduleTuck()
                        mode == 0 -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); toggleCard() }
                        mode == 1 -> settle(prefs, 0f, vy, switchSide = false)
                        mode == 2 -> settle(prefs, vx, vy, switchSide = true)
                    }
                    mode = 0
                }
            }
            true
        }
        wm.addView(zone, zoneParams)
        scheduleTuck(2000)
        main.postDelayed(breathRunnable, 6_000)
    }

    /** The side can be changed from Settings; the bar then glides across and the card follows it. */
    private fun applySideFromPrefs() {
        val right = overlayPrefs.getBoolean("right", false)
        if (right == onRight) return
        onRight = right
        placeBar()
        overlayPrefs.edit().remove("px").apply()      // reopen the card on the new side
        xAnim?.cancel()
        if (reduced) { zoneParams.x = restX(onRight); updateZone() }
        else xAnim = SpringAnimation(zoneParams, xProp).apply {
            spring = SpringForce(restX(onRight).toFloat()).setDampingRatio(0.85f).setStiffness(Motion.stiffness(0.5f))
            minimumVisibleChange = 1f; start()
        }
        bar.wake()
        scheduleTuck(1500)
    }

    /** Release: project where the flick is heading, snap to that edge, hand off the velocity. */
    private fun settle(prefs: android.content.SharedPreferences, vx: Float, vy: Float, switchSide: Boolean) {
        if (switchSide) {
            val projectedX = zoneParams.x + Motion.project(vx)
            onRight = projectedX + zoneW / 2f > screenW / 2f
            placeBar()
        }
        val targetY = (zoneParams.y + Motion.project(vy) * 0.35f).coerceIn(minY.toFloat(), maxY.toFloat())
        val flick = abs(vx) > 800f
        if (reduced) {
            zoneParams.x = restX(onRight); zoneParams.y = targetY.toInt(); updateZone()
        } else {
            xAnim = SpringAnimation(zoneParams, xProp).apply {
                spring = SpringForce(restX(onRight).toFloat())
                    .setDampingRatio(if (flick) 0.8f else 1f).setStiffness(Motion.stiffness(0.4f))
                setStartVelocity(vx); minimumVisibleChange = 1f; start()
            }
            yAnim = SpringAnimation(zoneParams, yProp).apply {
                spring = SpringForce(targetY).setDampingRatio(1f).setStiffness(Motion.stiffness(0.4f))
                setStartVelocity(vy); minimumVisibleChange = 1f; start()
            }
        }
        prefs.edit().putBoolean("right", onRight).putInt("y", targetY.toInt()).apply()
        if (switchSide && !reduced) main.postDelayed({ bar.landing() }, 240)   // squash as it lands on the edge
        scheduleTuck()
    }

    private fun scheduleTuck(delay: Long = 2500) {
        main.removeCallbacks(tuckRunnable)
        main.postDelayed(tuckRunnable, delay)
    }

    /** When idle, the sliver thins and fades so it stays out of the way. */
    private fun tuck() {
        if (panelOpen) return
        if (pendingShot != null) bar.tuckAway(partial = true) else bar.tuckAway()
    }

    // ---- idle breathing: one slow breath now and then, never a constant animation ----

    private val breathRunnable = object : Runnable {
        override fun run() {
            val interactive = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
            if (!panelOpen && interactive) bar.breatheOnce(strong = false)
            main.postDelayed(this, 11_000)
        }
    }

    // ---- while a screenshot is waiting the bar pulses green and sends out a ring now and then ----

    private val pendingPulse = object : Runnable {
        override fun run() {
            if (pendingShot == null) return
            bar.dotsOnce()
            main.postDelayed(this, 2600)
        }
    }
    private var pulseCount = 0

    // ---- screenshots: take one the normal way, the bar turns green, the note opens with it attached ----

    private fun checkForScreenshot() {
        val shot = Screenshots.latest(this) ?: return
        val fresh = System.currentTimeMillis() / 1000 - shot.addedSec < 30
        if (shot.id == lastShotId || !fresh) return
        lastShotId = shot.id
        val mode = shotMode()
        if (mode == "off") return                       // ignored; the card's screenshot button still works
        val open = editor
        if (mode == "auto" && panelOpen && open != null) { open.addImage(shot.uri); return }
        pendingShot = shot.uri
        if (panelOpen) showShotPrompt?.invoke()          // "ask": offer it on the open card
        bar.setTint(GREEN)
        main.removeCallbacks(tuckRunnable)
        bar.wake()
        pulseCount = 0
        main.removeCallbacks(pendingPulse)
        main.post(pendingPulse)
        main.removeCallbacks(expirePending)
        main.postDelayed(expirePending, forgetAfterMs())
        scheduleTuck(3000)
    }

    /** What to do with a new screenshot: "ask" (default), "auto" attach, or "off". Set in Settings. */
    private fun shotMode() = overlayPrefs.getString("shot_mode", "ask") ?: "ask"
    private var showShotPrompt: (() -> Unit)? = null
    private var hideChooserRef: (() -> Unit)? = null

    private fun clearPendingShot() {
        main.removeCallbacks(expirePending)
        hideChooserRef?.invoke()
        pendingShot = null
        main.removeCallbacks(pendingPulse)
        bar.setTint(pal.accent)
        scheduleTuck(500)
    }

    /** Attach the newest screenshot; without photo access, send the user to the app to allow it. */
    private fun attachLatestScreenshot(ed: NoteEditor) {
        if (!Screenshots.attachLatest(this, ed)) {
            Toast.makeText(this, "Allow photo access in Scratchy to attach screenshots", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- side panel ----

    /** Tap or inward swipe on the bar: open the card, or close it if it is already open. */
    private fun toggleCard() {
        if (panel != null) collapse() else expand()
    }

    /** Re-adds the bar so it sits above the card and the outside-tap catcher. */
    private fun raiseBar() {
        if (!::zone.isInitialized || !zone.isAttachedToWindow) return
        runCatching { wm.removeView(zone) }
        runCatching { wm.addView(zone, zoneParams) }
        bar.pressUp()       // the touch that opened the card never gets its "finger up"; release the pill ourselves
    }

    private fun expand() {
        if (panel != null) return
        panelOpen = true
        main.removeCallbacks(tuckRunnable)

        // With blur on, the window is exactly the card: the system blurs the whole window rectangle,
        // so a margin for the shadow would show blurred strips around the card.
        val blurPx = Appearance.blurRadiusPx(this)
        val margin = if (blurPx > 0) 0 else dp(10)
        val minW = dp(220); val minH = dp(180)
        val topLimit = dp(24)
        val saved = getSharedPreferences("overlay", MODE_PRIVATE)
        val panelW = saved.getInt("pw", (screenW * if (screenW > screenH) 0.5f else 0.72f).toInt())
            .coerceIn(minW, screenW - margin * 2)
        val panelH = saved.getInt("ph", (screenH * if (screenW > screenH) 0.8f else 0.46f).toInt())
            .coerceIn(minH, screenH - topLimit - margin * 2)

        val (startNote, continuing) = CardNote.pick(this)
        val ed = NoteEditor(ui, pal, startNote, compact = true) {
            NoteStore.save(this, it)
            if (!it.isBlank) CardNote.remember(this, it.id)
        }
        editor = ed
        if (shotMode() == "auto") {
            pendingShot?.let { ed.addImage(it); clearPending.run(); main.removeCallbacks(clearPending) }
        }

        val content = FrameLayout(ui)
        val recents = ScrollView(ui).apply { isVerticalScrollBarEnabled = false }
        content.addView(ed, FrameLayout.LayoutParams(-1, -1))
        content.addView(recents, FrameLayout.LayoutParams(-1, -1))
        recents.visibility = View.GONE

        cardPinned = overlayPrefs.getBoolean("card_pinned", false)
        val pinRef = arrayOfNulls<android.widget.ImageView>(1)
        val pinBtn = ui.iconButton(
            R.drawable.ic_pin, if (cardPinned) pal.accent else pal.secondary,
            "Pin card: keep it on screen and use the phone normally"
        ) {
            cardPinned = !cardPinned
            overlayPrefs.edit().putBoolean("card_pinned", cardPinned).apply()
            pinRef[0]?.setColorFilter(if (cardPinned) pal.accent else pal.secondary)
            pinRef[0]?.let {                      // a little pop so the change is felt
                it.scaleX = 1.3f; it.scaleY = 1.3f
                Motion.spring(it, DynamicAnimation.SCALE_X, 1f, 0.5f, 0.3f)
                Motion.spring(it, DynamicAnimation.SCALE_Y, 1f, 0.5f, 0.3f)
            }
            applyPinState(focusEditor = !cardPinned)
            Toast.makeText(
                this,
                if (cardPinned) "Pinned. Use your phone normally; tap the card to type."
                else "Unpinned. Tapping outside the card closes it.",
                Toast.LENGTH_SHORT
            ).show()
        }
        pinRef[0] = pinBtn
        var showContinuing: (Note?) -> Unit = {}     // set once the "Continuing" row exists (below)
        var hideChooser: () -> Unit = {}             // set once the screenshot chooser exists (below)
        var attachAfterPick: Uri? = null             // screenshot waiting for "Other note" to be chosen
        val header = LinearLayout(ui).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(R.drawable.ic_list, pal.secondary, "Recent notes") {
                hideChooser()
                val show = recents.visibility != View.VISIBLE
                if (!show) attachAfterPick = null
                if (show) refreshRecents(recents, ed, close = {
                    recents.visibility = View.GONE; ed.visibility = View.VISIBLE
                }, picked = { showContinuing(it) })
                recents.visibility = if (show) View.VISIBLE else View.GONE
                ed.visibility = if (show) View.GONE else View.VISIBLE
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            addView(ui.iconButton(R.drawable.ic_checklist, pal.secondary, "Checklist") {
                hideChooser(); ed.visibility = View.VISIBLE; recents.visibility = View.GONE
                ed.toggleChecklist()
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            addView(ui.iconButton(R.drawable.ic_paste, pal.secondary, "Paste") {
                hideChooser(); ed.visibility = View.VISIBLE; recents.visibility = View.GONE
                ed.pasteFromClipboard()
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            addView(ui.iconButton(R.drawable.ic_screenshot, pal.secondary, "Attach latest screenshot") {
                hideChooser(); ed.visibility = View.VISIBLE; recents.visibility = View.GONE
                attachLatestScreenshot(ed)
            }, LinearLayout.LayoutParams(dp(36), dp(40)))
            // Centre: grab handle. Dragging the header moves the card.
            addView(FrameLayout(ui).apply {
                addView(View(ui).apply { background = roundRect(pal.hairline, dp(2).toFloat()) },
                    FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(pinBtn, LinearLayout.LayoutParams(dp(36), dp(40)))
        }

        // When the card reopened on an existing note, say so and offer a fresh one in a tap.
        val continueInfo = TextView(ui).apply {
            text = "Continuing · edited ${formatWhen(startNote.updatedAt)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(pal.secondary)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val continueRow = LinearLayout(ui).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (continuing) View.VISIBLE else View.GONE
            addView(continueInfo, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(ui).apply {
                text = "＋ New note"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(pal.accent)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(10), dp(8), dp(2), dp(8))
                pressable(0.95f) {
                    ed.commit()
                    ed.load(NoteStore.newNote())
                    this@apply.let { (it.parent as View).visibility = View.GONE }
                    hideChooser(); ed.visibility = View.VISIBLE; recents.visibility = View.GONE
                    ed.bodyEdit.requestFocus()
                }
            })
        }
        // A new screenshot is waiting: ask where it goes before showing any note.
        val chooserPreview = android.widget.ImageView(ui).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundRect(pal.hairline, dp(10).toFloat())
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
            }
        }
        val chooserCol = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(6)) }
        val chooser = ScrollView(ui).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(chooserCol)
        }
        content.addView(chooser, FrameLayout.LayoutParams(-1, -1))
        fun currentNote(): Note? = if (!ed.note.isBlank) ed.note else CardNote.lastNote(this)
        fun choice(title: String, sub: String?, filled: Boolean, onTap: () -> Unit): View =
            LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                background = roundRect(if (filled) pal.accent else pal.hairline, dp(16).toFloat())
                addView(TextView(ui).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(if (filled) 0xFFFFFFFF.toInt() else pal.text)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                if (sub != null) addView(TextView(ui).apply {
                    text = sub
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (filled) 0xCCFFFFFF.toInt() else pal.secondary)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    bidi()
                })
                pressable(0.97f) { onTap() }
            }
        hideChooser = {
            chooserOpen = false
            chooser.visibility = View.GONE
            if (recents.visibility != View.VISIBLE) ed.visibility = View.VISIBLE
        }
        hideChooserRef = { hideChooser() }
        fun attachNow(uri: Uri) {
            ed.addImage(uri)
            main.removeCallbacks(clearPending); clearPending.run()
            hideChooser()
        }
        fun showChooser() {
            val uri = pendingShot ?: return
            ed.visibility = View.GONE; recents.visibility = View.GONE
            chooserCol.removeAllViews()
            chooserPreview.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= 29) Thread {
                val bmp = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(240, 360), null) }.getOrNull()
                main.post { bmp?.let(chooserPreview::setImageBitmap) }
            }.start()
            chooserCol.addView(LinearLayout(ui).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(chooserPreview, LinearLayout.LayoutParams(dp(44), dp(56)).apply { marginEnd = dp(12) })
                addView(TextView(ui).apply {
                    text = "Add this screenshot to…"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(pal.text)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val cur = currentNote()
            if (cur != null) chooserCol.addView(
                choice("Current note", cur.heading.ifEmpty { "Untitled" }, filled = true) {
                    if (ed.note.id != cur.id) { ed.commit(); ed.load(cur); CardNote.remember(this, cur.id) }
                    showContinuing(cur)
                    attachNow(uri)
                }, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
            chooserCol.addView(
                choice("New note", "Start a fresh note with it", filled = false) {
                    ed.commit(); ed.load(NoteStore.newNote()); showContinuing(null)
                    attachNow(uri)
                }, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
            chooserCol.addView(
                choice("Other note…", "Pick one from your notes", filled = false) {
                    attachAfterPick = uri
                    chooser.visibility = View.GONE
                    refreshRecents(recents, ed, close = {
                        recents.visibility = View.GONE; ed.visibility = View.VISIBLE
                    }, picked = { n ->
                        showContinuing(n)
                        attachAfterPick?.let { attachNow(it) }
                        attachAfterPick = null
                    })
                    recents.visibility = View.VISIBLE
                }, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(4) })
            chooserCol.addView(TextView(ui).apply {
                text = "Not now"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(pal.secondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(6))
                pressable(0.95f) { main.removeCallbacks(clearPending); clearPending.run() }
            }, LinearLayout.LayoutParams(-1, -2))
            chooser.visibility = View.VISIBLE
            chooserOpen = true
        }
        showShotPrompt = { if (shotMode() == "ask") showChooser() }
        if (pendingShot != null && shotMode() == "ask") showChooser()
        showContinuing = { n ->
            if (n == null) continueRow.visibility = View.GONE
            else {
                continueInfo.text = "Continuing · edited ${formatWhen(n.updatedAt)}"
                continueRow.visibility = View.VISIBLE
            }
        }
        val r = Appearance.cardRadius(this)
        val closeStrip = LinearLayout(ui).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            contentDescription = "Close note"
            addView(android.widget.ImageView(ui).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(pal.secondary)
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(TextView(ui).apply {
                text = "Close"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(pal.secondary)
            })
            pressable(0.985f) { collapse() }
        }
        val card = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
            background = Appearance.card(this@OverlayService, pal)
            // A see-through card would show its own shadow through itself, which the animation's snapshot
            // doesn't have, so the two looked different at the handover. Only opaque cards get a shadow.
            elevation = if (margin > 0 && Appearance.opacity(this@OverlayService) >= 100) dp(10).toFloat() else 0f
            addView(header, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(dp(10), 0, dp(10), 0) })
            addView(continueRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(10), 0) })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
            // The whole bottom edge of the card closes it (the note is already saved as you type).
            addView(View(ui).apply { setBackgroundColor(pal.hairline) }, LinearLayout.LayoutParams(-1, 1))
            addView(closeStrip, LinearLayout.LayoutParams(-1, dp(46)))
        }
        val grip = android.widget.ImageView(ui).apply {
            setImageResource(R.drawable.ic_grip)
            setColorFilter(pal.secondary)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "Resize"
            // The card is elevated; without this the grip is drawn (and touched) beneath it.
            elevation = dp(12).toFloat()
        }
        val p = PanelLayout(ui).apply {
            setPadding(margin, margin / 2, margin, margin)
            clipToPadding = false
            addView(card, FrameLayout.LayoutParams(-1, -1))
            addView(grip, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.RIGHT)
                .apply { setMargins(0, 0, margin + dp(2), margin + dp(2)) })
            onBack = { if (cardPinned) setPanelFocusable(false) else collapse() }
            onOutside = { if (cardPinned) setPanelFocusable(false) else collapse() }
        }

        val lp = WindowManager.LayoutParams(
            panelW + margin * 2, panelH + margin * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no NOT_FOCUSABLE) so the keyboard can attach.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                (if (cardPinned) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val defX = if (onRight) screenW - panelW - margin * 2 + margin else -margin
            x = saved.getInt("px", defX).coerceIn(-margin, screenW - panelW - margin)
            y = saved.getInt("py", dp(48)).coerceIn(topLimit - margin, screenH - panelH - margin * 2)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (blurPx > 0 && Build.VERSION.SDK_INT >= 31) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = blurPx
            }
        }
        panelLp = lp
        panelFocusable = !cardPinned
        // Pinned cards take focus only when you touch a text field, so the app behind keeps working.
        ed.onTouched = { v ->
            if (cardPinned && !panelFocusable) {
                setPanelFocusable(true)
                main.postDelayed({
                    v.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            }
        }
        // Tapping outside the card must only close it. Without this the same tap also reaches the app
        // underneath (a button, a video...). A see-through full-screen window under the card catches it.
        addDismissCatcher()
        val style = animStyle()
        val genieOpen = style == "genie" && !reduced
        if (genieOpen) {
            p.alpha = 0f                       // the real card stays invisible until the genie lands
            if (blurPx > 0 && Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = 0   // blur fades in with the genie
        }
        wm.addView(p, lp)
        panel = p
        // The bar stays visible while the card is open, and tapping it closes the card. Windows stack in
        // the order they were added, so put the bar back on top of the card and the catcher.
        main.removeCallbacks(tuckRunnable)
        bar.wake()
        main.post { raiseBar() }

        fun savePanel() = saved.edit()
            .putInt("px", lp.x).putInt("py", lp.y)
            .putInt("pw", lp.width - margin * 2).putInt("ph", lp.height - margin * 2).apply()

        // Move: drag the header 1:1, kept fully on screen.
        var gx = 0f; var gy = 0f; var sx = 0; var sy = 0
        header.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gx = e.rawX; gy = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (sx + e.rawX - gx).toInt().coerceIn(-margin, screenW - lp.width + margin)
                    lp.y = (sy + e.rawY - gy).toInt().coerceIn(topLimit - margin, screenH - lp.height + margin)
                    runCatching { wm.updateViewLayout(p, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanel()
            }
            true
        }
        // Resize: drag the corner grip, kept within the screen and above a minimum size.
        var sw = 0; var sh = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gx = e.rawX; gy = e.rawY; sw = lp.width; sh = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (sw + e.rawX - gx).toInt().coerceIn(minW + margin * 2, screenW - lp.x + margin)
                    lp.height = (sh + e.rawY - gy).toInt().coerceIn(minH + margin * 2, screenH - lp.y + margin)
                    runCatching { wm.updateViewLayout(p, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePanel()
            }
            true
        }

        if (genieOpen) {
            // Grow out of the bar: snapshot the laid-out card, play the genie backwards, then reveal it.
            whenSized(p) {
                val bmp = capture(p)
                val loc = IntArray(2); p.getLocationOnScreen(loc)
                if (blurPx > 0) animateBlur(p, lp, 0, blurPx, genieMs(420))
                runGenie(bmp, loc[0], loc[1], opening = true, live = p,
                    cardAlpha = Appearance.opacity(this) / 100f) {
                }
            }
        } else if (style == "slide" && !reduced) {
            // Slide in from the same edge the bar sits on.
            val off = (panelW + margin * 2).toFloat() * (if (onRight) 1 else -1)
            p.translationX = off; p.alpha = 0f
            Motion.spring(p, DynamicAnimation.TRANSLATION_X, 0f, 1f, 0.38f)
            Motion.spring(p, DynamicAnimation.ALPHA, 1f, 1f, 0.25f)
        }
        if (!cardPinned && pendingShot == null || !cardPinned && shotMode() != "ask") {
            ed.bodyEdit.requestFocus()
            main.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(ed.bodyEdit, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        }
    }

    /** Applies the pinned state to a card that is already open. */
    private fun applyPinState(focusEditor: Boolean) {
        catcher?.let { c ->
            val cl = c.layoutParams as WindowManager.LayoutParams
            cl.flags = if (cardPinned) cl.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else cl.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(c, cl) }
        }
        setPanelFocusable(!cardPinned)
        if (focusEditor) editor?.let { e ->
            e.bodyEdit.requestFocus()
            main.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(e.bodyEdit, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    /** Lets the card take (or give back) keyboard focus without closing it. */
    private fun setPanelFocusable(focusable: Boolean) {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (focusable == panelFocusable) return
        panelFocusable = focusable
        lp.flags = if (focusable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(p, lp) }
        if (!focusable) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(p.windowToken, 0)
        }
    }

    /** Fills the recents list: "New note" first, then the latest notes. Tap loads one into the editor. */
    private fun refreshRecents(host: ScrollView, ed: NoteEditor, close: () -> Unit, picked: (Note?) -> Unit) {
        host.removeAllViews()
        val col = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        fun row(label: String, accent: Boolean, onTap: () -> Unit) {
            col.addView(TextView(ui).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (accent) pal.accent else pal.text)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), dp(11), dp(6), dp(11))
                bidi()
                pressable(0.98f) { onTap() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        row("＋  New note", true) {
            ed.commit(); ed.load(NoteStore.newNote()); close(); picked(null); ed.bodyEdit.requestFocus()
            // a fresh note is only remembered once it has text (see the editor callback)
        }
        NoteStore.all(this).take(10).forEach { n ->
            row((if (n.pinned) "📌 " else "") + n.heading.ifEmpty { "Untitled" }, false) {
                ed.commit(); ed.load(n); CardNote.remember(this, n.id); close(); picked(n); ed.bodyEdit.requestFocus()
            }
        }
        host.addView(col)
    }

    /** Reverse the entrance along the same path: slide back to the edge. */
    private fun collapse() {
        val p = panel ?: return
        panel = null
        panelOpen = false
        panelLp = null
        showShotPrompt = null
        hideChooserRef = null
        removeDismissCatcher()
        editor?.commit()
        editor = null
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(p.windowToken, 0)
        // Bring the bar back right away, not after the animation ends.
        zone.visibility = View.VISIBLE
        bar.wake()
        bar.swell()          // react at the click, not half a second later when the card has finished closing

        fun removePanel() {
            // Make the whole window transparent and untouchable first, then remove it a beat
            // later. Removing it while its last drawn frame is still on screen flashes the
            // old panel for a few frames.
            runCatching {
                val wlp = p.layoutParams as WindowManager.LayoutParams
                wlp.alpha = 0f
                wlp.flags = wlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(p, wlp)
            }
            p.visibility = View.INVISIBLE
            main.postDelayed({ runCatching { wm.removeView(p) } }, 120)
        }
        fun done() {
            applySideFromPrefs()
            scheduleTuck()
        }
        val style = animStyle()
        when {
            reduced || style == "none" -> { removePanel(); done() }
            style == "genie" -> {
                val bmp = capture(p)
                val loc = IntArray(2); p.getLocationOnScreen(loc)
                runGenie(bmp, loc[0], loc[1], opening = false, live = p,
                    cardAlpha = Appearance.opacity(this) / 100f) {
                    done()
                }
                val wlp = p.layoutParams as WindowManager.LayoutParams
                val blurNow = if (Build.VERSION.SDK_INT >= 31) wlp.blurBehindRadius else 0
                if (blurNow > 0) {
                    // The card fades via the crossfade; keep the window so its blur can fade out with the genie.
                    animateBlur(p, wlp, blurNow, 0, genieMs(480)) { removePanel() }
                } else {
                    main.postDelayed({ removePanel() }, genieMs(300))   // after the crossfade has handed over
                }
            }
            else -> {
                val off = p.width.toFloat() * (if (onRight) 1 else -1)
                Motion.spring(p, DynamicAnimation.ALPHA, 0f, 1f, 0.25f)
                Motion.spring(p, DynamicAnimation.TRANSLATION_X, off, 1f, 0.3f) { removePanel(); done() }
            }
        }
    }

    // ---- outside-tap catcher ----

    private var catcher: View? = null

    private fun addDismissCatcher() {
        removeDismissCatcher()
        val v = View(ui).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) collapse()
                true                                   // swallow the whole gesture, nothing leaks through
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (cardPinned) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        wm.addView(v, lp)
        catcher = v
    }

    private fun removeDismissCatcher() {
        catcher?.let { runCatching { wm.removeView(it) } }
        catcher = null
    }

    // ---- genie animation ----

    private val genieViews = mutableListOf<View>()

    /** Genie speed from Settings, 50%..200% (100% = the default feel). Every genie timing is divided by it. */
    private fun genieMs(base: Long): Long =
        (base * 100f / overlayPrefs.getInt("genie_speed", 100).coerceIn(50, 200)).toLong()

    /** Eases the window's blur radius so it never pops on or off. */
    private fun animateBlur(view: View, lp: WindowManager.LayoutParams, from: Int, to: Int, ms: Long, onEnd: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 31) { onEnd?.invoke(); return }
        ValueAnimator.ofInt(from, to).apply {
            duration = ms
            addUpdateListener {
                lp.blurBehindRadius = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
            start()
        }
    }

    private fun animStyle() = overlayPrefs.getString("anim", "genie") ?: "genie"

    private fun capture(v: View): Bitmap {
        val b = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun whenSized(v: View, block: () -> Unit) {
        if (v.width > 0 && v.isLaidOut) { block(); return }
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int,
            ) {
                if (view.width > 0) { view.removeOnLayoutChangeListener(this); block() }
            }
        })
    }

    /** Plays the genie between the card snapshot and the bar, on its own click-through full-screen window. */
    private fun runGenie(
        bmp: Bitmap, ox: Int, oy: Int, opening: Boolean,
        live: View,                        // the real card, crossfaded with the snapshot
        cardAlpha: Float,                  // the card's fill opacity (0..1)
        onEnd: () -> Unit,
    ) {
        val bl = IntArray(2); zone.getLocationOnScreen(bl)
        val gv = GenieView(
            ui, bmp, ox.toFloat(), oy.toFloat(),
            if (onRight) bl[0] + zone.width - bar.sliverCenterPx else bl[0] + bar.sliverCenterPx,
            bl[1] + zone.height / 2f, onRight,
            initialT = if (opening) 1f else 0f
        )
        val glp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not FLAG_NOT_TOUCHABLE: since Android 12 a click-through overlay is capped at ~80% opacity,
            // which made the card look see-through. Touches are blocked for the ~0.4s the animation runs.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        wm.addView(gv, glp)
        genieViews.add(gv)

        // Crossfade between the snapshot and the live card (see GenieView.snapshotShare). The live
        // card's visibility is eased so it is nearly complete early; the snapshot's share only drops
        // steeply as that last bit arrives, and easing stretches that part over many frames instead
        // of two, so any tiny difference between the two copies fades instead of popping.
        fun crossfade(fadeIn: Boolean, ms: Long, done: () -> Unit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                addUpdateListener {
                    val tau = it.animatedValue as Float
                    val inv = 1f - tau
                    val l = if (fadeIn) 1f - inv * inv * inv else 1f - tau * tau * tau
                    live.alpha = l
                    gv.fade = GenieView.snapshotShare(l, cardAlpha)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { done() }
                })
                start()
            }
        }
        fun removeSnapshot() {
            runCatching { wm.removeView(gv) }
            genieViews.remove(gv)
            bmp.recycle()
        }

        if (!opening) {
            gv.fade = 0f
            crossfade(false, genieMs(260)) {}   // live card out, snapshot in, as the genie starts
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = genieMs(if (opening) 420 else 480)
            interpolator = if (opening) DecelerateInterpolator(1.3f) else AccelerateDecelerateInterpolator()
            addUpdateListener {
                val a = it.animatedValue as Float
                gv.t = if (opening) 1f - a else a
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                    if (opening) crossfade(true, genieMs(260)) { live.alpha = 1f; main.postDelayed({ removeSnapshot() }, 16) }
                    else main.postDelayed({ removeSnapshot() }, 16)
                }
            })
            start()
        }
    }


    /** Window root that reports Back key and outside touches. */
    private class PanelLayout(ctx: Context) : FrameLayout(ctx) {
        var onBack: (() -> Unit)? = null
        var onOutside: (() -> Unit)? = null

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack?.invoke(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) { onOutside?.invoke(); return true }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        const val ACTION_STOP = "com.scratchy.app.STOP"
        private const val CHANNEL = "scratchy_overlay"
        private const val GREEN = 0xFF34C759.toInt()
        @Volatile var running = false
    }
}
