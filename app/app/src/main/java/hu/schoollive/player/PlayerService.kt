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
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.*

private const val TAG = "PlayerService"
private const val NOTIF_CHANNEL = "schoollive_player"
private const val NOTIF_ID = 1
private const val BEACON_INTERVAL_MS = 30_000L
private const val BELLS_REFRESH_INTERVAL_MS = 300_000L   // 5 min

class PlayerService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService() = this@PlayerService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── State ─────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var snapClient: SnapcastClient? = null
    private var syncClient: SyncClient? = null

    var snapConnected = false
        private set
    var wsConnected = false
        private set

    // ── Callbacks (set by MainActivity) ───────────────────────────────────────
    var onMessage: ((String) -> Unit)? = null
    var onPlay: ((Long) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSnapStateChanged: ((Boolean) -> Unit)? = null
    var onWsStateChanged: ((Boolean) -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        connectAll()
        scope.launch { beaconLoop() }
        checkOtaInBackground()
        scope.launch { bellRefreshLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        snapClient?.stop()
        syncClient?.stop()
        scope.cancel()
    }

    // ── Connect everything ────────────────────────────────────────────────────

    private fun connectAll() {
        val ctx = applicationContext
        val host = PrefsUtil.getSnapHost(ctx)
        val port = PrefsUtil.getSnapPort(ctx)
        val deviceKey = PrefsUtil.getDeviceKey(ctx)
        val serverUrl = PrefsUtil.getServerUrl(ctx)

        // Snapcast
        if (host.isNotEmpty() && port > 0) {
            snapClient?.stop()
            snapClient = SnapcastClient(
                host = host,
                port = port,
                onConnected = {
                    snapConnected = true
                    onSnapStateChanged?.invoke(true)
                    updateNotification("Playing")
                },
                onDisconnected = {
                    snapConnected = false
                    onSnapStateChanged?.invoke(false)
                    updateNotification("Audio disconnected")
                }
            )
            snapClient?.start()
        }

        // SyncEngine WebSocket
        if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
            val wsUrl = buildWsUrl(serverUrl, deviceKey)
            syncClient?.stop()
            syncClient = SyncClient(
                wsUrl = wsUrl,
                onPrepare = { _, _ -> /* AudioTrack init handled in SnapcastClient */ },
                onPlay = { ms -> onPlay?.invoke(ms) },
                onStop = { onStop?.invoke() },
                onMessage = { text -> onMessage?.invoke(text) },
                onConnected = {
                    wsConnected = true
                    onWsStateChanged?.invoke(true)
                },
                onDisconnected = {
                    wsConnected = false
                    onWsStateChanged?.invoke(false)
                }
            )
            syncClient?.start()
        }
    }

    private fun buildWsUrl(serverUrl: String, deviceKey: String): String {
        val base = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
        return "$base/sync?deviceKey=$deviceKey"
    }

    // ── Volume control (from MainActivity) ────────────────────────────────────

    fun setVolume(percent: Int) {
        snapClient?.setVolume(percent)
    }

    // ── Beacon loop ───────────────────────────────────────────────────────────

    private suspend fun beaconLoop() {
        while (scope.isActive) {
            try {
                val ctx = applicationContext
                val serverUrl = PrefsUtil.getServerUrl(ctx)
                val deviceKey = PrefsUtil.getDeviceKey(ctx)
                if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
                    ApiClient.get(serverUrl).beacon(
                        deviceKey,
                        BeaconRequest(
                            deviceKey = deviceKey,
                            snapConnected = snapConnected,
                            wsConnected = wsConnected,
                            volume = 100
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Beacon error: ${e.message}")
            }
            delay(BEACON_INTERVAL_MS)
        }
    }

    // ── Bell refresh loop ─────────────────────────────────────────────────────

    private suspend fun bellRefreshLoop() {
        while (scope.isActive) {
            try {
                val ctx = applicationContext
                val serverUrl = PrefsUtil.getServerUrl(ctx)
                val deviceKey = PrefsUtil.getDeviceKey(ctx)
                if (serverUrl.isNotEmpty() && deviceKey.isNotEmpty()) {
                    val resp = ApiClient.get(serverUrl).getBells(deviceKey)
                    if (resp.isSuccessful) {
                        resp.body()?.bells?.let { bells ->
                            PrefsUtil.setBellsJson(
                                ctx,
                                com.google.gson.Gson().toJson(bells)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bell refresh error: ${e.message}")
            }
            delay(BELLS_REFRESH_INTERVAL_MS)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL,
                "SchoolLive Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background audio player" }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }
}

// ── OTA check (called once on service start) ──────────────────────────────────
private fun checkOtaInBackground() {
    scope.launch {
        delay(10_000)   // wait 10s after startup before checking
        try {
            val updateUrl = hu.schoollive.player.util.OtaManager(applicationContext).checkForUpdate()
            if (updateUrl != null) {
                Log.i("PlayerService", "OTA update available: $updateUrl")
                // Download happens on main thread via DownloadManager
                withContext(Dispatchers.Main) {
                    hu.schoollive.player.util.OtaManager(applicationContext)
                        .downloadAndInstall(updateUrl)
                }
            }
        } catch (e: Exception) {
            Log.w("PlayerService", "OTA check error: ${e.message}")
        }
    }
}
