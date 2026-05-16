package hu.schoollive.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import hu.schoollive.player.util.OtaCheckWorker
import hu.schoollive.player.util.OtaManager
import hu.schoollive.player.util.PrefsUtil
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

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

    // Aktuális automatikus re-mute job (durationMs lejárta után visszanémítja
    // a snap kimenetet a háttér-állapotba). Új lejátszás indítása lemondja.
    private var remuteJob: Job? = null

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
        scheduleOtaPeriodicCheck()
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
                deviceId = PrefsUtil.getDeviceId(ctx),
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
                    // HUD-megjelenítés CSAK ha a kliens célzott. Nem célzott
                    // klienseken eddig megjelent a HUD, pedig a hang nem szólt.
                    val targeted = applyTargeting(event.unmutedDeviceIds, event.durationMs)
                    if (targeted) onBell?.invoke(event)
                },

                onTts   = { event ->
                    val targeted = applyTargeting(event.unmutedDeviceIds, event.durationMs)
                    if (targeted) onTts?.invoke(event)
                },
                onRadio = { event ->
                    // Rádiónál nincs durationMs → STOP_PLAYBACK-ig unmuted marad
                    val targeted = applyTargeting(event.unmutedDeviceIds, null)
                    if (targeted) onRadio?.invoke(event)
                },
                onStop  = {
                    // Lejátszás vége → minden esetben visszanémítjuk a snap kimenetet.
                    remuteJob?.cancel(); remuteJob = null
                    snapClient?.setLocalMute(true)
                    onStop?.invoke()
                },
                // Backend SET_VOLUME (admin UI slider / mute gomb): a kapott
                // 0..10 érték a snap stream lokális hangerejére hat.
                // A SnapcastClient.setVolume 0..100 skálát vár, ezért × 10.
                // (A média notification UI tvVolume frissítését nem itt
                // csináljuk – a MainActivity figyel beacon-állapot frissítésre.)
                onSetVolume = { vol ->
                    val percent = (vol * 10).coerceIn(0, 100)
                    Log.d(TAG, "Backend SET_VOLUME → $percent%")
                    snapClient?.setVolume(percent)
                },
                // Backend MUTE: localMute toggle a snap streamre. Unmute után
                // a userVolume visszaáll (SnapcastClient kezeli a tárolt értéket).
                onMute = { muted ->
                    Log.d(TAG, "Backend MUTE → $muted")
                    snapClient?.setLocalMute(muted)
                },
                // NOW_PLAYING_INFO: forrás-csere broadcast az audio-mixer
                // `source:start` eventjén. A payload mostantól tartalmazza:
                //   • text  – TTS-nél a teljes felolvasandó szöveg ékezetekkel
                //   • targetDeviceIds – az új forrás célzása (null = ALL)
                //
                // A célzást ÚJRA alkalmazzuk a snap-streamre (a backend snap-
                // szervere ezt amúgy is megtette per-client RPC-vel, de a
                // kliens localMute flag-jét is el kell igazítanunk, hogy a
                // korábbi PREPARE-en megszerzett mute állapot helyett az új
                // forrás célzását tükrözze). Ha az új célzás KIzár minket,
                // localMute aktív marad + HUD skip. Ha BENNE vagyunk vagy
                // ALL, localMute fel + HUD megy.
                onNowPlayingInfo = { info ->
                    val unmutedIds = info.targetDeviceIds ?: emptyList()
                    val targeted   = applyTargeting(unmutedIds, info.durationMs)
                    if (!targeted) {
                        Log.d(TAG, "NOW_PLAYING_INFO ${info.jobType}: nem célzott → HUD skip")
                        return@SyncClient
                    }
                    val now = System.currentTimeMillis()
                    when (info.jobType) {
                        "BELL" -> onBell?.invoke(BellEvent(
                            soundFile        = info.title,
                            playAtMs         = now,
                            durationMs       = info.durationMs,
                            snapActive       = true,
                            unmutedDeviceIds = unmutedIds,
                        ))
                        "TTS" -> onTts?.invoke(TtsEvent(
                            // TTS-nél a `text` mező a felolvasott teljes szöveg
                            // (ékezetekkel), a `title` a rövid kontextus. A HUD
                            // overlay a text-et részesíti előnyben (lásd
                            // MainActivity); fallback a title-re ha üres.
                            text             = info.text ?: "",
                            title            = if (info.title.isNotEmpty()) info.title else "Hangos közlemény",
                            playAtMs         = now,
                            durationMs       = info.durationMs,
                            snapActive       = true,
                            unmutedDeviceIds = unmutedIds,
                        ))
                        "RADIO" -> onRadio?.invoke(RadioEvent(
                            title            = if (info.title.isNotEmpty()) info.title else "Iskolarádió",
                            snapActive       = true,
                            unmutedDeviceIds = unmutedIds,
                        ))
                    }
                },
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

    /** Lazy Android system AudioManager – a STREAM_MUSIC (media) volume-ot
     *  állítjuk vele a snapclient saját puffer-gain-jén túl. Ez biztosítja,
     *  hogy a backend SET_VOLUME parancsa tényleg a fizikai kimenetig
     *  érvényesüljön; ha a system media-volume halk vagy néma, hiába
     *  állítjuk az AudioTrack volume-ját 1.0-ra. */
    private val sysAudio: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    fun setVolume(percent: Int) {
        snapClient?.setVolume(percent)
        // Android STREAM_MUSIC (media) volume is mozogjon.
        try {
            val clamped = percent.coerceIn(0, 100)
            val max = sysAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * clamped / 100).coerceIn(0, max)
            sysAudio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            // DND/Notification policy access nélkül Android dobhat: ignore.
            Log.w("PlayerService", "setStreamVolume denied: ${e.message}")
        } catch (e: Exception) {
            Log.w("PlayerService", "setStreamVolume error: ${e.message}")
        }
    }

    /** Backend MUTE parancs – a snap localMute mellett az Android media
     *  stream-et is 0-ra állítja. Az unmute parancs nem visszaállítja a
     *  hangerőt (mert az user-szinten változhat közben), csak a snap localMute-ot
     *  oldja. A backend külön SET_VOLUME parancsa fogja a hangerőt visszaadni. */
    fun setMute(muted: Boolean) {
        snapClient?.setLocalMute(muted)
        if (muted) {
            try {
                sysAudio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (e: Exception) {
                Log.w("PlayerService", "setStreamVolume mute error: ${e.message}")
            }
        }
        // Unmute esetén nem touch-elunk – a következő SET_VOLUME (vagy user
        // hangerő-gombja) hozza vissza a kívánt szintet.
    }

    /** Backend fordított targetingjének lokális fallbackje.
     *
     *  A snap stream alapból néma (localMuted = true). Ha a kliens benne van
     *  az `unmutedDeviceIds` listában, oldjuk a némítást a lejátszás idejére.
     *  Ha durationMs ismert (BELL/TTS), automatikusan visszanémítjuk; rádiónál
     *  STOP_PLAYBACK-ig nyitva marad. */
    /**
     * Visszatérési érték: true ha a kliens célzott (vagy "uncertain" – akkor
     * is szól), false ha biztosan NEM célzott. A HUD/overlay megjelenítését
     * is ennek alapján szabályozzuk – ha false, a hívó NE invoke-olja a
     * HUD-callback-et (lásd onBell/onTts/onRadio).
     */
    private fun applyTargeting(unmutedDeviceIds: List<String>, durationMs: Long?): Boolean {
        val myId = PrefsUtil.getDeviceId(applicationContext)
        val sc   = snapClient ?: return true

        // Mute-ot CSAK akkor alkalmazunk, ha:
        //   1. A saját device.id ismert (nem üres)
        //   2. A backend küldött egy nem-üres célzási listát
        //   3. A saját ID nincs benne a listában
        //
        // Ha bármelyik feltétel nem teljesül (pl. friss eszköz, még nincs
        // device.id, vagy a lista üres) → NEM némítunk (backward-compat).
        val certainlyNotTargeted = myId.isNotEmpty()
            && unmutedDeviceIds.isNotEmpty()
            && !unmutedDeviceIds.contains(myId)

        // Bármi is történik, az előző auto-remute timert lemondjuk.
        remuteJob?.cancel(); remuteJob = null

        if (certainlyNotTargeted) {
            // Biztosan nem célzott → néma + HUD-ot sem mutatunk
            sc.setLocalMute(true)
            Log.d(TAG, "Targeting: NEM célzott (myId=$myId), snap localMuted=true, HUD skip")
            return false
        }

        // Célzott, vagy uncertain (ID hiányzik / lista üres) → szól + HUD megy
        sc.setLocalMute(false)
        Log.d(TAG, "Targeting: célzott vagy unknown (myId=${myId.ifEmpty{"N/A"}}), snap localMuted=false, dur=$durationMs")

        // Auto-remute durationMs lejártakor.
        durationMs?.let { dur ->
            if (dur > 0) {
                remuteJob = scope.launch {
                    // Egy kis biztonsági margó, hogy a lejátszás teljesen befejeződjön.
                    delay(dur + 3000L)
                    snapClient?.setLocalMute(true)
                    Log.d(TAG, "Auto-remute durationMs (${dur}ms + safety) lejárt")
                }
            }
        }
        return true
    }

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
                    // Backend által visszaadott deviceId perzisztálása.
                    // Ezt használja a snap HELLO `ID` és az unmutedDeviceIds fallback.
                    val newId = resp.body()?.deviceId
                    if (!newId.isNullOrEmpty() && PrefsUtil.getDeviceId(ctx) != newId) {
                        PrefsUtil.setDeviceId(ctx, newId)
                        Log.d(TAG, "DeviceId persisted from beacon: $newId")
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

    // 6 óránként periodikus GitHub Releases ellenőrzés WorkManager-rel.
    // A `KEEP` policy biztosítja, hogy szolgáltatás-újraindításkor (pl.
    // a rendszer START_STICKY-vel feléleszti) ne ütemezzük újra a már
    // futó periodikus munkát – a meglévő ütemezés megmarad.
    //
    // A `NetworkType.CONNECTED` constraint miatt csak akkor fut, ha van
    // hálózat – Wi-Fi szakadáskor nem pörög feleslegesen.
    //
    // Pár: a `checkOtaInBackground()` (10 s indítás-utáni egyszeri check)
    // megmarad, mert az induló kliens azonnal megkapja a friss verziót;
    // a periodikus worker pedig a 24/7 üzemelő eszközöknél biztosítja,
    // hogy a service-restart nélkül is eljusson a frissítés.
    private fun scheduleOtaPeriodicCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<OtaCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "schoollive-ota-check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodikus OTA check ütemezve (6h, NETWORK_CONNECTED)")
        } catch (e: Exception) {
            Log.w(TAG, "Periodikus OTA ütemezés hiba: ${e.message}")
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