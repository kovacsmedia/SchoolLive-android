package hu.schoollive.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import hu.schoollive.player.R

/**
 * Státuszjelző widget – pont + felirat.
 *
 * Három állapot:
 *   CONNECTED   → zöld, statikus; aktivitáskor pulse() villan egyet
 *   CONNECTING  → narancssárga, 500ms-enként villogó
 *   OFFLINE     → piros, statikus
 *
 * Aktivitás LED viselkedés (pulse()):
 *   CONNECTED állapotban rövid (~150ms) sötétülés, majd visszatér zöldre.
 *   Mint a hálózati switch LED-je adatátvitelkor.
 *   Triggerek:
 *     - Snap: SnapcastClient minden audio chunk beérkezésekor
 *     - Net:  SyncClient minden WS üzenet beérkezésekor
 *
 * Használat XML-ből:
 *   <hu.schoollive.player.ui.StatusIndicatorView
 *       android:id="@+id/indicatorSnap"
 *       android:layout_width="wrap_content"
 *       android:layout_height="wrap_content"
 *       app:label="snap" />
 */
class StatusIndicatorView @JvmOverloads constructor(
    context:  Context,
    attrs:    AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    enum class State { CONNECTED, CONNECTING, OFFLINE }

    private val DOT_SIZE_DP   = 12f
    private val BLINK_MS      = 500L
    private val PULSE_MS      = 150L   // aktivitás villanás időtartama
    private val LABEL_SIZE_SP = 9f

    private val dotView   = DotView(context)
    private val labelView = TextView(context)
    private val handler   = Handler(Looper.getMainLooper())

    private var state        = State.OFFLINE
    private var blinkOn      = true
    private var blinkRunning = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            dotView.setColor(if (blinkOn) colorAmber else colorDim)
            if (blinkRunning) handler.postDelayed(this, BLINK_MS)
        }
    }

    // Pulse: rövid sötétülés után visszatér zöldre
    private val pulseRestoreRunnable = Runnable {
        if (state == State.CONNECTED) dotView.setColor(colorGreen)
    }

    private val colorGreen = Color.parseColor("#22c55e")
    private val colorAmber = Color.parseColor("#f59e0b")
    private val colorRed   = Color.parseColor("#ef4444")
    private val colorDim   = Color.parseColor("#1a2d47")   // "kikapcsolt" villogásnál
    private val colorPulse = Color.parseColor("#0a3020")   // sötét zöld – aktivitás jelzés

    init {
        orientation = VERTICAL
        gravity     = Gravity.CENTER_HORIZONTAL

        val density = context.resources.displayMetrics.density
        val dotPx   = (DOT_SIZE_DP * density).toInt()

        dotView.layoutParams = LayoutParams(dotPx, dotPx)
        addView(dotView)

        labelView.textSize     = LABEL_SIZE_SP
        labelView.setTextColor(Color.parseColor("#4a6280"))
        labelView.gravity      = Gravity.CENTER
        labelView.letterSpacing = 0.05f
        labelView.setPadding(0, (2 * density).toInt(), 0, 0)
        addView(labelView)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.StatusIndicatorView)
            val lbl = a.getString(R.styleable.StatusIndicatorView_label) ?: ""
            labelView.text = lbl.uppercase()
            a.recycle()
        }

        setState(State.OFFLINE)
    }

    fun setLabel(label: String) {
        labelView.text = label.uppercase()
    }

    // ── Állapot váltás ────────────────────────────────────────────────────────

    fun setState(newState: State) {
        if (state == newState) return
        state = newState
        stopBlink()
        when (newState) {
            State.CONNECTED  -> dotView.setColor(colorGreen)
            State.CONNECTING -> startBlink()
            State.OFFLINE    -> dotView.setColor(colorRed)
        }
    }

    // ── Aktivitás villanás ────────────────────────────────────────────────────
    // CONNECTED állapotban: sötét zöld → 150ms → visszaáll zöldre.
    // Sűrű hívás esetén (pl. audio stream) a restore runnable mindig újraindul,
    // így folyamatos adatátvitelnél gyorsan pulzál, szünetben visszaáll.

    fun pulse() {
        if (state != State.CONNECTED) return
        handler.removeCallbacks(pulseRestoreRunnable)
        dotView.setColor(colorPulse)
        handler.postDelayed(pulseRestoreRunnable, PULSE_MS)
    }

    // ── Belső ─────────────────────────────────────────────────────────────────

    private fun startBlink() {
        blinkOn      = true
        blinkRunning = true
        dotView.setColor(colorAmber)
        handler.postDelayed(blinkRunnable, BLINK_MS)
    }

    private fun stopBlink() {
        blinkRunning = false
        handler.removeCallbacks(blinkRunnable)
        handler.removeCallbacks(pulseRestoreRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBlink()
        handler.removeCallbacks(pulseRestoreRunnable)
    }

    // ── Pont widget ───────────────────────────────────────────────────────────

    private inner class DotView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorRed }

        fun setColor(color: Int) { paint.color = color; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width  / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, minOf(cx, cy), paint)
        }
    }
}