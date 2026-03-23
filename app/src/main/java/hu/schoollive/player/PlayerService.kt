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
import hu.schoollive.player.sync.SyncClient
import hu.schoollive.player.ui.BellManager
import hu.schoollive.player.util.OtaManager
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.*

private const val TAG = "PlayerService"
private const val NOTIF_CHANNEL = "schoollive_player"
private const val NOTIF_ID = 1
private const val BEACON_INTERVAL_MS = 30_000L
private const val BELLS_REFRESH_INTERVAL_MS = 300_000L   // 5 perc

class PlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@PlayerService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapClient: SnapcastClient? = null
    private var syncClient: SyncClient? = null
    private var bellManager: BellManager? = null

    var snapConnected = false; private set
    var wsConnected = false; private set

    var onMessage: ((String) -> Unit)? = null
    var onPlay: ((Long) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSnapStateChanged: ((Boolean) -> Unit)? = null
    var onWsStateChanged: ((Boolean) -> Unit)? = null
    var onBellsUpdated: (() -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        bellManager = BellManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
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
        scope.cancel()
    }

    // ── BellManager elérése a MainActivity-ből ────────────────────────────────

    fun getBellManager(): BellManager? = bellManager

    // ── Connect ───────────────────────────────────────────────────────────────

    private fun connectAll() {
        val ctx = applicationContext
        val host = PrefsUtil.getSnapHost(ctx)
        val port = PrefsUtil.getSnapPort(ctx)
        val deviceKey = PrefsUtil.getDeviceKey(ctx)
        val serverUrl = PrefsUtil.getServerUrl(ctx)

        if (host.isNotEmpty() && port > 0) {
            snapClient?.stop()
            snapClient = SnapcastClient(
                host = host, port = port,
                onConnected = {
                    snapConnected = true
                    onSnapStateChanged?.invoke(true)
                    updateNotification("Lejátszás")
                },
                onDisconnected = {
                    snapConnected = false
                    onSnapStateChanged?.invoke(false)
                    updateNotification("Audio kapcsolat megszakadt")
                }
            )
            snapClient?.start()
        }

        if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
            val wsUrl = serverUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                .trimEnd('/') + "/sync?deviceKey=$deviceKey"
            syncClient?.stop()
            syncClient = SyncClient(
                wsUrl = wsUrl,
                onPrepare = { _, _ -> },
                onPlay    = { ms -> onPlay?.invoke(ms) },
                onStop    = { onStop?.invoke() },
                onMessage = { text -> onMessage?.invoke(text) },
                onConnected    = { wsConnected = true;  onWsStateChanged?.invoke(true) },
                onDisconnected = { wsConnected = false; onWsStateChanged?.invoke(false) }
            )
            syncClient?.start()
        }
    }

    fun setVolume(percent: Int) { snapClient?.setVolume(percent) }

    // ── Beacon loop ───────────────────────────────────────────────────────────

    private suspend fun beaconLoop() {
        while (scope.isActive) {
            try {
                val ctx = applicationContext
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
                            appVersion    = "Android ${android.os.Build.VERSION.RELEASE}"
                        )
                    )
                    // 401 = eszközt visszatették provisioning-ba a frontenden
                    if (resp.code() == 401) {
                        Log.w(TAG, "Beacon 401 – eszköz deaktiválva, visszatérés provisioning-ba")
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
        val intent = Intent(ctx, ProvisioningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        ctx.startActivity(intent)
        stopSelf()
    }

    // ── Bell refresh loop ─────────────────────────────────────────────────────

    private suspend fun bellRefreshLoop() {
        while (scope.isActive) {
            refreshBells()
            delay(BELLS_REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun refreshBells() {
        try {
            val ctx = applicationContext
            val serverUrl = PrefsUtil.getServerUrl(ctx)
            val deviceKey = PrefsUtil.getDeviceKey(ctx)
            if (serverUrl.isEmpty() || deviceKey.isEmpty()) return

            val resp = ApiClient.get(serverUrl).getBells(deviceKey)
            if (!resp.isSuccessful) {
                Log.w(TAG, "Bell refresh failed: ${resp.code()}")
                return
            }

            val body = resp.body() ?: return

            val bells = if (body.isHoliday) {
                // Ünnepnap – nincs csengetés
                Log.d(TAG, "Bell refresh: mai nap ünnepnap, nincs csengetés")
                emptyList()
            } else {
                Log.d(TAG, "Bell refresh: ${body.bells.size} csengetés betöltve")
                body.bells
            }

            bellManager?.updateBells(bells)
            withContext(Dispatchers.Main) { onBellsUpdated?.invoke() }

        } catch (e: Exception) {
            Log.w(TAG, "Bell refresh error: ${e.message}")
        }
    }

    // ── OTA check ────────────────────────────────────────────────────────────

    private fun checkOtaInBackground() {
        scope.launch {
            delay(10_000)
            try {
                val ctx = applicationContext
                val url = OtaManager(ctx).checkForUpdate()
                if (url != null) {
                    Log.i(TAG, "OTA update available: $url")
                    withContext(Dispatchers.Main) {
                        OtaManager(ctx).downloadAndInstall(url)
                    }
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
                NOTIF_CHANNEL, "SchoolLive Player", NotificationManager.IMPORTANCE_LOW
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