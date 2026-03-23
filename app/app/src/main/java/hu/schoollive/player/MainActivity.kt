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
import hu.schoollive.player.databinding.ActivityMainBinding
import hu.schoollive.player.ui.BellManager
import hu.schoollive.player.util.PrefsUtil
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bellManager: BellManager

    // Service binding
    private var playerService: PlayerService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playerService = (binder as PlayerService.LocalBinder).getService()
            serviceBound = true
            attachServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
    }

    // UI handlers
    private val clockHandler  = Handler(Looper.getMainLooper())
    private val controlHandler = Handler(Looper.getMainLooper())
    private var volume = 100

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If not provisioned → go to setup
        if (!PrefsUtil.isProvisioned(this)) {
            startActivity(Intent(this, ProvisioningActivity::class.java))
            finish()
            return
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFullscreen()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bellManager = BellManager(this)

        setupUi()
        startClock()
        startAndBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        controlHandler.removeCallbacksAndMessages(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ── Fullscreen ────────────────────────────────────────────────────────────

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
            onSnapStateChanged = { connected ->
                runOnUiThread {
                    binding.indicatorSnap.setColorFilter(
                        if (connected) getColor(R.color.green_ok) else getColor(R.color.red_err)
                    )
                }
            }
            onWsStateChanged = { connected ->
                runOnUiThread {
                    binding.indicatorWs.setColorFilter(
                        if (connected) getColor(R.color.green_ok) else getColor(R.color.orange_radio)
                    )
                }
            }
            onMessage = { text ->
                runOnUiThread { showMessageOverlay(text) }
            }
            onPlay = { _ ->
                runOnUiThread { showRadioOverlay() }
            }
            onStop = {
                runOnUiThread { hideOverlay() }
            }
        }
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupUi() {
        binding.tvTenantName.text = PrefsUtil.getDeviceName(this)

        // Volume controls
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

        // Tap to show controls
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) toggleControls()
            true
        }

        // Initially hide control panel
        binding.controlPanel.visibility = View.GONE
        binding.overlayMessage.visibility = View.GONE
        binding.overlayRadio.visibility = View.GONE
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun startClock() {
        val clockFmt  = SimpleDateFormat("HH:mm", Locale.getDefault())
        val secondFmt = SimpleDateFormat("ss", Locale.getDefault())

        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                binding.tvClock.text = clockFmt.format(now)
                binding.tvSeconds.text = secondFmt.format(now)
                binding.tvNextBell.text = bellManager.nextBellTime()
                    ?.let { "Következő csengetés: $it" } ?: ""
                clockHandler.postDelayed(this, 500)
            }
        }
        clockHandler.post(tick)
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private fun showMessageOverlay(text: String) {
        hideOverlay()
        binding.tvOverlayMessage.text = text
        // Scale font size based on text length (matching Python player behaviour)
        val sp = when {
            text.length < 40  -> 48f
            text.length < 80  -> 36f
            text.length < 150 -> 28f
            else              -> 22f
        }
        binding.tvOverlayMessage.textSize = sp
        binding.overlayMessage.visibility = View.VISIBLE
        binding.overlayMessage.alpha = 0f
        binding.overlayMessage.animate().alpha(1f).setDuration(300).start()
    }

    private fun showRadioOverlay() {
        hideOverlay()
        binding.overlayRadio.visibility = View.VISIBLE
        binding.overlayRadio.alpha = 0f
        binding.overlayRadio.animate().alpha(1f).setDuration(300).start()
        pulseRadioBar()
    }

    private fun hideOverlay() {
        binding.overlayMessage.animate().alpha(0f).setDuration(200).withEndAction {
            binding.overlayMessage.visibility = View.GONE
        }.start()
        binding.overlayRadio.animate().alpha(0f).setDuration(200).withEndAction {
            binding.overlayRadio.visibility = View.GONE
        }.start()
    }

    private fun pulseRadioBar() {
        if (binding.overlayRadio.visibility != View.VISIBLE) return
        binding.viewRadioBar.animate()
            .scaleX(1.5f).scaleY(1.2f).setDuration(600)
            .withEndAction {
                binding.viewRadioBar.animate()
                    .scaleX(1f).scaleY(1f).setDuration(600)
                    .withEndAction { pulseRadioBar() }
                    .start()
            }.start()
    }

    // ── Controls panel ────────────────────────────────────────────────────────

    private fun toggleControls() {
        if (binding.controlPanel.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        binding.controlPanel.visibility = View.VISIBLE
        binding.controlPanel.alpha = 0f
        binding.controlPanel.animate().alpha(1f).setDuration(200).start()
        autoHideControls()
    }

    private fun hideControls() {
        binding.controlPanel.animate().alpha(0f).setDuration(200).withEndAction {
            binding.controlPanel.visibility = View.GONE
        }.start()
        controlHandler.removeCallbacksAndMessages(null)
    }

    private fun autoHideControls() {
        controlHandler.removeCallbacksAndMessages(null)
        controlHandler.postDelayed({ hideControls() }, 10_000)
    }
}
