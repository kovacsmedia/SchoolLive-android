package hu.schoollive.player

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import hu.schoollive.player.api.ApiClient
import hu.schoollive.player.databinding.ActivityMainBinding
import hu.schoollive.player.sync.BellEvent
import hu.schoollive.player.sync.RadioEvent
import hu.schoollive.player.sync.TtsEvent
import hu.schoollive.player.util.PrefsUtil
import hu.schoollive.player.ui.StatusIndicatorView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var playerService: PlayerService? = null
    private var serviceBound  = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playerService = (binder as PlayerService.LocalBinder).getService()
            serviceBound  = true
            attachServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    private val clockHandler     = Handler(Looper.getMainLooper())
    private val controlHandler   = Handler(Looper.getMainLooper())
    private val overlayHandler   = Handler(Looper.getMainLooper())
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var volume = 100

    private enum class OverlayState { NONE, BELL, MESSAGE, RADIO }
    private var currentOverlay    = OverlayState.NONE
    private var overlayDurationMs = 0L
    private var overlayStartMs    = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PrefsUtil.isProvisioned(this)) {
            startActivity(Intent(this, ProvisioningActivity::class.java))
            finish(); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFullscreen()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUi()
        startClock()
        startAndBindService()
        loadTenantInfo()
        checkProvisioningStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        controlHandler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        countdownHandler.removeCallbacksAndMessages(null)
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private fun startAndBindService() {
        val intent = Intent(this, PlayerService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun attachServiceCallbacks() {
        playerService?.apply {

            // ── Snap pont ─────────────────────────────────────────────────────
            // PlayerService hívja onSnapStateChanged-et Bool-lal,
            // de az indikátor három állapotú. CONNECTING állapotot a
            // SnapcastClient kapcsolódási fázisában jelzi – lásd PlayerService.
            onSnapStateChanged = { connected ->
                runOnUiThread {
                    binding.indicatorSnap.setState(
                        if (connected) StatusIndicatorView.State.CONNECTED
                        else           StatusIndicatorView.State.OFFLINE
                    )
                    if (!connected && currentOverlay == OverlayState.RADIO) hideOverlay()
                }
            }

            onSnapConnecting = {
                runOnUiThread {
                    binding.indicatorSnap.setState(StatusIndicatorView.State.CONNECTING)
                }
            }

            // ── Net pont ──────────────────────────────────────────────────────
            onWsStateChanged = { connected ->
                runOnUiThread {
                    binding.indicatorNet.setState(
                        if (connected) StatusIndicatorView.State.CONNECTED
                        else           StatusIndicatorView.State.OFFLINE
                    )
                }
            }

            onWsConnecting = {
                runOnUiThread {
                    binding.indicatorNet.setState(StatusIndicatorView.State.CONNECTING)
                }
            }

            // Aktivitás LED pulzálás – switch LED effekt adatátvitelkor
            onSnapActivity = { runOnUiThread { binding.indicatorSnap.pulse() } }
            onNetActivity  = { runOnUiThread { binding.indicatorNet.pulse()  } }

            // ── Bell ──────────────────────────────────────────────────────────
            onBell = { event -> runOnUiThread { handleBell(event) } }

            // ── TTS ───────────────────────────────────────────────────────────
            onTts = { event -> runOnUiThread { handleTts(event) } }

            // ── Rádió ─────────────────────────────────────────────────────────
            onRadio = { event -> runOnUiThread { handleRadio(event) } }

            // ── Stop ──────────────────────────────────────────────────────────
            onStop = { runOnUiThread { hideOverlay() } }

            onBellsUpdated = { /* clock tick kezeli */ }
        }
    }

    // ── Esemény kezelők ───────────────────────────────────────────────────────

    private fun handleBell(event: BellEvent) {
        showBellOverlay(event.durationMs)
    }

    private fun handleTts(event: TtsEvent) {
        if (currentOverlay == OverlayState.BELL) return
        if (!event.snapActive) return
        if (event.text.isNotEmpty()) showMessageOverlay(event.text, event.durationMs)
    }

    private fun handleRadio(event: RadioEvent) {
        if (currentOverlay == OverlayState.BELL ||
            currentOverlay == OverlayState.MESSAGE) return
        if (!event.snapActive) return
        showRadioOverlay(event.title)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showBellOverlay(durationMs: Long?) {
        cancelAllTimers()
        hideAllOverlaysInternal()
        currentOverlay = OverlayState.BELL
        binding.overlayBell.visibility = View.VISIBLE
        binding.overlayBell.alpha      = 0f
        binding.overlayBell.animate().alpha(1f).setDuration(200).start()
        if (durationMs != null && durationMs > 0) startOverlayCountdown(durationMs)
        else scheduleAutoHide(15_000L)
    }

    private fun showMessageOverlay(text: String, durationMs: Long?) {
        cancelAllTimers()
        hideAllOverlaysInternal()
        currentOverlay = OverlayState.MESSAGE
        binding.tvOverlayMessage.text = text
        binding.tvOverlayMessage.textSize = when {
            text.length < 40  -> 48f
            text.length < 80  -> 36f
            text.length < 150 -> 28f
            else              -> 22f
        }
        binding.overlayMessage.visibility = View.VISIBLE
        binding.overlayMessage.alpha      = 0f
        binding.overlayMessage.animate().alpha(1f).setDuration(300).start()
        if (durationMs != null && durationMs > 0) startOverlayCountdown(durationMs)
        else scheduleAutoHide(60_000L)
    }

    private fun showRadioOverlay(title: String) {
        cancelAllTimers()
        hideAllOverlaysInternal()
        currentOverlay = OverlayState.RADIO
        binding.tvRadioTitle.text       = title
        binding.overlayRadio.visibility = View.VISIBLE
        binding.overlayRadio.alpha      = 0f
        binding.overlayRadio.animate().alpha(1f).setDuration(300).start()
        pulseRadioBar()
    }

    private fun startOverlayCountdown(durationMs: Long) {
        countdownHandler.removeCallbacksAndMessages(null)
        overlayDurationMs = durationMs
        overlayStartMs    = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                val elapsed   = System.currentTimeMillis() - overlayStartMs
                val remaining = overlayDurationMs - elapsed
                if (remaining <= 0) { hideOverlay(); return }
                val progress = ((elapsed.toFloat() / overlayDurationMs) * 100).toInt()
                when (currentOverlay) {
                    OverlayState.BELL -> {
                        binding.overlayProgressBar.progress = progress
                        binding.tvOverlayCountdown.text =
                            if (remaining > 1000) "${remaining / 1000} mp" else ""
                    }
                    OverlayState.MESSAGE -> {
                        binding.overlayProgressBarMsg.progress = progress
                        binding.tvOverlayCountdownMsg.text =
                            if (remaining > 1000) "${remaining / 1000} mp" else ""
                    }
                    else -> {}
                }
                countdownHandler.postDelayed(this, 200)
            }
        }
        countdownHandler.post(ticker)
    }

    fun hideOverlay() {
        cancelAllTimers()
        currentOverlay    = OverlayState.NONE
        overlayDurationMs = 0L
        hideAllOverlaysInternal()
    }

    private fun hideAllOverlaysInternal() {
        listOf(binding.overlayBell, binding.overlayMessage, binding.overlayRadio).forEach { v ->
            v.animate().alpha(0f).setDuration(200)
                .withEndAction { v.visibility = View.GONE }.start()
        }
    }

    private fun cancelAllTimers() {
        overlayHandler.removeCallbacksAndMessages(null)
        countdownHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleAutoHide(delayMs: Long) {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, delayMs)
    }

    private fun pulseRadioBar() {
        if (binding.overlayRadio.visibility != View.VISIBLE) return
        binding.viewRadioBar?.animate()
            ?.scaleX(1.6f)?.scaleY(1.3f)?.setDuration(600)
            ?.withEndAction {
                binding.viewRadioBar?.animate()
                    ?.scaleX(1f)?.scaleY(1f)?.setDuration(600)
                    ?.withEndAction { pulseRadioBar() }?.start()
            }?.start()
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.tvTenantName.text = PrefsUtil.getDeviceName(this)
        binding.btnVolUp.setOnClickListener {
            volume = minOf(100, volume + 10)
            playerService?.setVolume(volume)
            binding.tvVolume.text = "$volume%"
            autoHideControls()
        }
        binding.btnVolDown.setOnClickListener {
            volume = maxOf(0, volume - 10)
            playerService?.setVolume(volume)
            binding.tvVolume.text = "$volume%"
            autoHideControls()
        }
        binding.tvVolume.text = "$volume%"
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) toggleControls()
            true
        }
        binding.controlPanel.visibility        = View.GONE
        binding.overlayMessage.visibility      = View.GONE
        binding.overlayRadio.visibility        = View.GONE
        binding.overlayBell.visibility         = View.GONE
        binding.bellPill.visibility            = View.GONE
        binding.bellNotificationBar.visibility = View.GONE

        // Kezdeti indikátor állapot
        binding.indicatorSnap.setState(StatusIndicatorView.State.CONNECTING)
        binding.indicatorNet.setState(StatusIndicatorView.State.CONNECTING)
    }

    // ── Óra ───────────────────────────────────────────────────────────────────

    private fun startClock() {
        val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFmt  = SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu"))
        val tick = object : Runnable {
            override fun run() {
                val now  = Date()
                binding.tvClock.text = clockFmt.format(now)
                binding.tvDate.text  = dateFmt.format(now)
                val next = playerService?.getBellManager()?.nextBellTime()
                binding.bellPill.visibility = if (next != null) View.VISIBLE else View.GONE
                if (next != null) binding.tvNextBell.text = next
                clockHandler.postDelayed(this, 500)
            }
        }
        clockHandler.post(tick)
    }

    // ── Tenant info ───────────────────────────────────────────────────────────

    private fun loadTenantInfo() {
        val serverUrl = PrefsUtil.getServerUrl(this)
        val deviceKey = PrefsUtil.getDeviceKey(this)
        if (serverUrl.isEmpty() || deviceKey.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = ApiClient.get(serverUrl).getTenantInfo(deviceKey)
                if (resp.isSuccessful) {
                    val name = resp.body()?.tenantName
                    if (!name.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { binding.tvTenantName.text = name }
                        PrefsUtil.setDeviceName(this@MainActivity, name)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkProvisioningStatus() {
        val serverUrl  = PrefsUtil.getServerUrl(this)
        val hardwareId = hu.schoollive.player.util.DeviceIdUtil.getHardwareId(this)
        if (serverUrl.isEmpty() || hardwareId.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = ApiClient.get(serverUrl).getStatus(hardwareId)
                if (resp.isSuccessful && resp.body()?.status != "active") {
                    withContext(Dispatchers.Main) {
                        PrefsUtil.setProvisioned(this@MainActivity, false)
                        startActivity(Intent(this@MainActivity, ProvisioningActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                        finish()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun toggleControls() {
        if (binding.controlPanel.visibility == View.VISIBLE) hideControls()
        else showControls()
    }

    private fun showControls() {
        binding.controlPanel.visibility = View.VISIBLE
        binding.controlPanel.alpha      = 0f
        binding.controlPanel.animate().alpha(1f).setDuration(200).start()
        autoHideControls()
    }

    private fun hideControls() {
        binding.controlPanel.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.controlPanel.visibility = View.GONE }.start()
        controlHandler.removeCallbacksAndMessages(null)
    }

    private fun autoHideControls() {
        controlHandler.removeCallbacksAndMessages(null)
        controlHandler.postDelayed({ hideControls() }, 10_000)
    }
}