package hu.schoollive.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import hu.schoollive.player.api.ApiClient
import hu.schoollive.player.api.models.BeaconRequest
import hu.schoollive.player.snapcast.SnapcastClient
import hu.schoollive.player.sync.BellEvent
import hu.schoollive.player.sync.RadioEvent
import hu.schoollive.player.sync.SyncClient
import hu.schoollive.player.sync.TtsEvent
import hu.schoollive.player.ui.BellManager
import hu.schoollive.player.util.OtaManager
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.*

private const val TAG = "PlayerService"
private const val NOTIF_CHANNEL             = "schoollive_player"
private const val NOTIF_ID                  = 1
private const val BEACON_INTERVAL_MS        = 30_000L
private const val BELLS_REFRESH_INTERVAL_MS = 300_000L

class PlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@PlayerService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapClient: SnapcastClient? = null
    private var syncClient: SyncClient?     = null
    private var bellManager: BellManager?   = null

    var snapConnected = false; private set
    var wsConnected   = false; private set

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onSnapStateChanged: ((Boolean) -> Unit)? = null
    var onSnapConnecting:   (() -> Unit)?         = null
    var onWsStateChanged:   ((Boolean) -> Unit)?  = null
    var onWsConnecting:     (() -> Unit)?          = null
    var onSnapActivity:     (() -> Unit)?          = null   // ← snap LED pulse
    var onNetActivity:      (() -> Unit)?          = null   // ← net LED pulse
    var onBellsUpdated:     (() -> Unit)?          = null

    var onBell:  ((BellEvent)  -> Unit)? = null
    var onTts:   ((TtsEvent)   -> Unit)? = null
    var onRadio: ((RadioEvent) -> Unit)? = null
    var onStop:  (() -> Unit)?            = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        val bm = BellManager(applicationContext)
        bm.onOfflineBell = { soundFile ->
            onBell?.invoke(BellEvent(
                soundFile  = soundFile,
                playAtMs   = System.currentTimeMillis(),
                durationMs = null,
                snapActive = false,
            ))
        }
        bellManager = bm
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Csatlakozás…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        connectAll()
        scope.launch { beaconLoop() }
        scope.launch { bellRefreshLoop() }
        checkOtaInBackground()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        snapClient?.stop()
        syncClient?.stop()
        bellManager?.stop()
        scope.cancel()
    }

    fun getBellManager(): BellManager? = bellManager

    // ── Connect ───────────────────────────────────────────────────────────────

    private fun connectAll() {
        val ctx       = applicationContext
        val host      = PrefsUtil.getSnapHost(ctx)
        val port      = PrefsUtil.getSnapPort(ctx)
        val deviceKey = PrefsUtil.getDeviceKey(ctx)
        val serverUrl = PrefsUtil.getServerUrl(ctx)

        // ── Snapcast ──────────────────────────────────────────────────────────
        if (host.isNotEmpty() && port > 0) {
            // Azonnal jelezzük a csatlakozási kísérletet → villogó narancs
            onSnapConnecting?.invoke()

            snapClient?.stop()
            snapClient = SnapcastClient(
                host = host,
                port = port,
                onConnected = {
                    snapConnected = true
                    bellManager?.onSnapConnected()
                    onSnapStateChanged?.invoke(true)
                    updateNotification("Lejátszás")
                },
                onDisconnected = {
                    snapConnected = false
                    bellManager?.onSnapDisconnected()
                    onSnapStateChanged?.invoke(false)
                    onSnapConnecting?.invoke()
                    updateNotification("Audio kapcsolat megszakadt")
                },
                onActivity = { onSnapActivity?.invoke() },
            )
            snapClient?.start()
        }

        // ── WebSocket ─────────────────────────────────────────────────────────
        if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
            // Azonnal jelezzük a csatlakozási kísérletet → villogó narancs
            onWsConnecting?.invoke()

            val wsUrl = serverUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/') + "/sync?deviceKey=$deviceKey"

            syncClient?.stop()
            syncClient = SyncClient(
                wsUrl = wsUrl,

                onBell = { event ->
                    bellManager?.registerBell()
                    onBell?.invoke(event)
                },

                onTts   = { event -> onTts?.invoke(event) },
                onRadio = { event -> onRadio?.invoke(event) },
                onStop  = { onStop?.invoke() },
                onSyncBells = { scope.launch { refreshBells() } },
                onConnected = {
                    wsConnected = true
                    bellManager?.onWsConnected()
                    onWsStateChanged?.invoke(true)
                },
                onDisconnected = {
                    wsConnected = false
                    bellManager?.onWsDisconnected()
                    onWsStateChanged?.invoke(false)
                    onWsConnecting?.invoke()
                },
                onActivity = { onNetActivity?.invoke() },
            )
            syncClient?.start()
        }
    }

    fun setVolume(percent: Int) { snapClient?.setVolume(percent) }

    // ── Beacon ────────────────────────────────────────────────────────────────

    private suspend fun beaconLoop() {
        while (scope.isActive) {
            try {
                val ctx       = applicationContext
                val serverUrl = PrefsUtil.getServerUrl(ctx)
                val deviceKey = PrefsUtil.getDeviceKey(ctx)
                if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
                    val resp = ApiClient.get(serverUrl).beacon(
                        deviceKey,
                        BeaconRequest(
                            deviceKey     = deviceKey,
                            snapConnected = snapConnected,
                            wsConnected   = wsConnected,
                            volume        = 100,
                            platform      = "android",
                            appVersion    = "Android ${Build.VERSION.RELEASE}"
                        )
                    )
                    if (resp.code() == 401) {
                        Log.w(TAG, "Beacon 401 – deaktiválva")
                        withContext(Dispatchers.Main) { resetToProvisioning() }
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Beacon error: ${e.message}")
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    private fun resetToProvisioning() {
        val ctx = applicationContext
        PrefsUtil.setProvisioned(ctx, false)
        PrefsUtil.setSnapPort(ctx, 0)
        snapClient?.stop()
        syncClient?.stop()
        ctx.startActivity(Intent(ctx, ProvisioningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        stopSelf()
    }

    // ── Bell refresh ──────────────────────────────────────────────────────────

    private suspend fun bellRefreshLoop() {
        while (scope.isActive) {
            refreshBells()
            delay(BELLS_REFRESH_INTERVAL_MS)
        }
    }

    suspend fun refreshBells() {
        try {
            val ctx       = applicationContext
            val serverUrl = PrefsUtil.getServerUrl(ctx)
            val deviceKey = PrefsUtil.getDeviceKey(ctx)
            if (serverUrl.isEmpty() || deviceKey.isEmpty()) return
            val resp  = ApiClient.get(serverUrl).getBells(deviceKey)
            if (!resp.isSuccessful) return
            val body  = resp.body() ?: return
            val bells = if (body.isHoliday) emptyList() else body.bells
            Log.d(TAG, "Bell refresh: ${bells.size} csengetés (holiday=${body.isHoliday})")
            bellManager?.updateBells(bells)
            withContext(Dispatchers.Main) { onBellsUpdated?.invoke() }
        } catch (e: Exception) {
            Log.w(TAG, "Bell refresh error: ${e.message}")
        }
    }

    // ── OTA ───────────────────────────────────────────────────────────────────

    private fun checkOtaInBackground() {
        scope.launch {
            delay(10_000)
            try {
                val ctx = applicationContext
                val url = OtaManager(ctx).checkForUpdate()
                if (url != null) {
                    Log.i(TAG, "OTA update available: $url")
                    withContext(Dispatchers.Main) { OtaManager(ctx).downloadAndInstall(url) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OTA check error: ${e.message}")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "SchoolLive Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Háttér audio lejátszás" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("SchoolLive")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status))
    }
}