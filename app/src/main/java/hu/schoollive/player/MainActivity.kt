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
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var playerService: PlayerService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playerService = (binder as PlayerService.LocalBinder).getService()
            serviceBound = true
            attachServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName) { serviceBound = false }
    }

    private val clockHandler    = Handler(Looper.getMainLooper())
    private val controlHandler  = Handler(Looper.getMainLooper())
    private val overlayHandler  = Handler(Looper.getMainLooper())
    private var volume = 100

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!PrefsUtil.isProvisioned(this)) {
            startActivity(Intent(this, ProvisioningActivity::class.java))
            finish()
            return
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
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

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
            onMessage = { text -> runOnUiThread { showMessageOverlay(text) } }
            onPlay    = { _    -> runOnUiThread {
                if (binding.overlayMessage.visibility != View.VISIBLE) showRadioOverlay()
                scheduleAutoHide()
            }}
            onStop    = { runOnUiThread { hideOverlay() } }
            onBellsUpdated = { /* clock tick kezeli */ }
        }
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
            } catch (e: Exception) { /* cachelelt név marad */ }
        }
    }

    // ── Provisioning check ────────────────────────────────────────────────────

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
            } catch (e: Exception) { /* hálózati hiba – maradunk */ }
        }
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

        binding.controlPanel.visibility   = View.GONE
        binding.overlayMessage.visibility = View.GONE
        binding.overlayRadio.visibility   = View.GONE
        binding.bellPill.visibility       = View.GONE
        binding.bellNotificationBar.visibility = View.GONE
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun startClock() {
        val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFmt  = SimpleDateFormat("yyyy. MMMM d., EEEE", Locale("hu"))

        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                binding.tvClock.text = clockFmt.format(now)
                binding.tvDate.text  = dateFmt.format(now)

                val next = playerService?.getBellManager()?.nextBellTime()
                if (next != null) {
                    binding.bellPill.visibility = View.VISIBLE
                    binding.tvNextBell.text = next
                } else {
                    binding.bellPill.visibility = View.GONE
                }

                clockHandler.postDelayed(this, 500)
            }
        }
        clockHandler.post(tick)
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private fun showMessageOverlay(text: String) {
        hideOverlay()
        binding.tvOverlayMessage.text = text
        binding.tvOverlayMessage.textSize = when {
            text.length < 40  -> 48f
            text.length < 80  -> 36f
            text.length < 150 -> 28f
            else              -> 22f
        }
        binding.overlayMessage.visibility = View.VISIBLE
        binding.overlayMessage.alpha = 0f
        binding.overlayMessage.animate().alpha(1f).setDuration(300).start()
        scheduleAutoHide()
    }

    private fun showRadioOverlay() {
        hideOverlay()
        binding.overlayRadio.visibility = View.VISIBLE
        binding.overlayRadio.alpha = 0f
        binding.overlayRadio.animate().alpha(1f).setDuration(300).start()
        pulseRadioBar()
    }

    private fun hideOverlay() {
        overlayHandler.removeCallbacksAndMessages(null)
        binding.overlayMessage.animate().alpha(0f).setDuration(200).withEndAction {
            binding.overlayMessage.visibility = View.GONE
        }.start()
        binding.overlayRadio.animate().alpha(0f).setDuration(200).withEndAction {
            binding.overlayRadio.visibility = View.GONE
        }.start()
    }

    private fun scheduleAutoHide(delayMs: Long = 60_000L) {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({ hideOverlay() }, delayMs)
    }

    private fun pulseRadioBar() {
        if (binding.overlayRadio.visibility != View.VISIBLE) return
        binding.viewRadioBar.animate()
            .scaleX(1.6f).scaleY(1.3f).setDuration(600)
            .withEndAction {
                binding.viewRadioBar.animate()
                    .scaleX(1f).scaleY(1f).setDuration(600)
                    .withEndAction { pulseRadioBar() }
                    .start()
            }.start()
    }

    // ── Controls panel ────────────────────────────────────────────────────────

    private fun toggleControls() {
        if (binding.controlPanel.visibility == View.VISIBLE) hideControls() else showControls()
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