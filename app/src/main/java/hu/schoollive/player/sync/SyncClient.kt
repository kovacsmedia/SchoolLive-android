package hu.schoollive.player.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "SyncClient"

private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS = 30_000L

private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun isoNow(): String = synchronized(ISO_FORMAT) {
    ISO_FORMAT.format(Date())
}

data class BellEvent(
    val soundFile: String,
    val playAtMs: Long,
    val durationMs: Long?,
    val snapActive: Boolean,

    /**
     * A backend fordított targetingje.
     *
     * Ha nem üres, csak az ebben szereplő device.id-k vannak megcélozva.
     */
    val unmutedDeviceIds: List<String> = emptyList(),
)

data class TtsEvent(
    val text: String,
    val title: String,
    val playAtMs: Long,
    val durationMs: Long?,
    val snapActive: Boolean,
    val unmutedDeviceIds: List<String> = emptyList(),
)

data class RadioEvent(
    val title: String,
    val snapActive: Boolean,
    val unmutedDeviceIds: List<String> = emptyList(),
)

/**
 * JSON Array → List<String>.
 */
private fun jsonStringList(json: JSONObject, key: String): List<String> {
    if (!json.has(key) || json.isNull(key)) return emptyList()

    val arr = json.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())

    for (i in 0 until arr.length()) {
        val v = arr.optString(i, "")
        if (v.isNotEmpty()) out.add(v)
    }

    return out
}

/**
 * NOW_PLAYING_INFO esemény – a backend `audio-mixer onSourceStart` push-ja.
 * Forrás-csere (pl. TTS megszakítja a rádiót → TTS vége → RADIO resume) után
 * a HUD-frissítéshez használjuk. NEM tartalmaz targeting-listát (a backend
 * minden tenant-eszközre broadcast-olja), ezért a kliens dönt arról saját
 * localMuted állapota alapján, hogy ténylegesen mutat-e HUD-ot.
 */
data class NowPlayingInfo(
    val jobType: String,    // "BELL" | "TTS" | "RADIO"
    val title: String,
    val sourceType: String,
    val durationMs: Long?,
)

class SyncClient(
    private val wsUrl: String,
    private val onBell: (BellEvent) -> Unit = {},
    private val onTts: (TtsEvent) -> Unit = {},
    private val onRadio: (RadioEvent) -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSyncBells: () -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    // NOW_PLAYING_INFO push (forrás-csere HUD-frissítés). A kliensnek itt
    // KELL a localMuted-et néznie, mert ez a push minden eszközre megy,
    // célzás-listával együtt nem.
    private val onNowPlayingInfo: (NowPlayingInfo) -> Unit = {},

    // Net LED pulse trigger – minden beérkező WS üzenetnél hívódik.
    private val onActivity: () -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var activeWs: WebSocket? = null

    private var reconnectDelay = RECONNECT_BASE_MS

    /**
     * Server-óra → kliens-óra eltolódás (ms). A backend HELLO üzenetében
     * érkezik `serverNowMs` mezőben; a kliens kiszámolja:
     *   serverClockOffsetMs = serverNowMs - System.currentTimeMillis()
     * Aztán minden PLAY üzenet `playAtMs`-ét a server-órán kell értelmezni:
     *   delayMs = playAtMs - (System.currentTimeMillis() + serverClockOffsetMs)
     *
     * Az Android óra gyakran nem NTP-szinkronizált (mobilon több sec-es
     * eltérés is lehet), és anélkül a HUD/audio dispatch akár 3 sec
     * csúszással jött az ESP-hez képest.
     */
    @Volatile
    private var serverClockOffsetMs: Long = 0L

    /** Server-időt ad kliens-órán, az offset-tel korrigálva. */
    private fun serverNow(): Long = System.currentTimeMillis() + serverClockOffsetMs

    private val pendingPrepare = mutableMapOf<String, JSONObject>()

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient = run {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        scope.launch {
            connectLoop()
        }
    }

    fun stop() {
        activeWs?.close(1000, "Stopped")
        activeWs = null
        isConnected = false
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            if (!isConnected) {
                activeWs?.close(1000, "Reconnecting")
                activeWs = null

                Log.d(TAG, "Connecting to $wsUrl")

                activeWs = client.newWebSocket(
                    Request.Builder().url(wsUrl).build(),
                    listener
                )
            }

            delay(reconnectDelay)
        }
    }

    private fun sendReadyAck(commandId: String) {
        activeWs?.send(
            JSONObject().apply {
                put("type", "READY_ACK")
                put("commandId", commandId)
                put("bufferMs", 0)
                put("readyAt", isoNow())
            }.toString()
        )

        Log.d(TAG, "READY_ACK: $commandId")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS connected")

            isConnected = true
            reconnectDelay = RECONNECT_BASE_MS

            webSocket.send(
                JSONObject().apply {
                    put("type", "TIME_SYNC")
                    put("seq", System.currentTimeMillis())
                }.toString()
            )

            // Periodikus TIME_SYNC: 60 sec-enként újra-kérjük a backend
            // serverNow-t, hogy az NTP-szinkronizálatlan Android-óra drift-jét
            // (akár 1 sec/óra) folyamatosan korrigáljuk. A serverClockOffsetMs
            // így friss marad → a HUD/audio dispatch a PLAY playAtMs-én pontosan
            // azonos időpontban indul minden klienseken (ESP, Android, Linux).
            scope.launch {
                while (isConnected) {
                    delay(60_000L)
                    if (!isConnected) break
                    val ws = activeWs ?: break
                    try {
                        ws.send(
                            JSONObject().apply {
                                put("type", "TIME_SYNC")
                                put("seq", System.currentTimeMillis())
                            }.toString()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Periodic TIME_SYNC failed: ${e.message}")
                        break
                    }
                }
            }

            scope.launch(Dispatchers.Main) {
                onConnected()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")

            isConnected = false
            activeWs = null
            reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX_MS)

            scope.launch(Dispatchers.Main) {
                onDisconnected()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed: $code $reason")

            isConnected = false
            activeWs = null

            scope.launch(Dispatchers.Main) {
                onDisconnected()
            }
        }
    }

    private fun handleMessage(text: String) {
        // Net LED pulse – minden beérkező WS üzenet aktivitást jelez.
        onActivity()

        try {
            val json = JSONObject(text)

            val phase = json.optString("phase", "")
            val type = json.optString("type", "")
            val action = json.optString("action", "")
            val snapActive = json.optBoolean("snapcastActive", false)

            when {
                // ── PREPARE: csak tároljuk + ACK ─────────────────────────────
                phase == "PREPARE" -> {
                    val commandId = json.optString("commandId", "")

                    Log.d(
                        TAG,
                        "PREPARE: action=$action commandId=$commandId snap=$snapActive"
                    )

                    pendingPrepare[commandId] = json
                    sendReadyAck(commandId)
                }

                // ── PLAY: overlay + hang triggerelés ──────────────────────────
                phase == "PLAY" -> {
                    val commandId = json.optString("commandId", "")
                    val playAtMs = json.optLong("playAtMs", System.currentTimeMillis())
                    val durationMs = if (json.has("durationMs")) json.getLong("durationMs") else null

                    val prepare = pendingPrepare.remove(commandId) ?: run {
                        Log.w(TAG, "PLAY: nincs PREPARE párja: $commandId")
                        return
                    }

                    val prepAction = prepare.optString("action", "")
                    val prepSnapActive = prepare.optBoolean("snapcastActive", false)

                    // A backend a fordított targetinghez `unmutedDeviceIds`-t ad át,
                    // ami akár PLAY-ben, akár PREPARE-ben érkezhet.
                    val unmuted = jsonStringList(json, "unmutedDeviceIds")
                        .ifEmpty { jsonStringList(prepare, "unmutedDeviceIds") }

                    // FONTOS: a playAtMs server-órán, ezért a kliens-órát az
                    // offset-tel korrigálva (serverNow()) hasonlítjuk össze. NTP-
                    // szinkronizálatlan Android-óra mellett az offset több sec is
                    // lehet – e nélkül a HUD/audio 1-3 sec-cel elcsúszott az ESP-hez
                    // képest.
                    val diffMs = playAtMs - serverNow()

                    Log.d(
                        TAG,
                        "PLAY: action=$prepAction diffMs=${diffMs}ms dur=${durationMs}ms unmuted=${unmuted.size} offset=$serverClockOffsetMs"
                    )

                    if (diffMs < -10_000L) {
                        Log.w(TAG, "PLAY stale (${-diffMs}ms) → skip")
                        return
                    }

                    val delayMs = if (diffMs > 0) diffMs else 0L

                    scope.launch {
                        if (delayMs > 0) delay(delayMs)

                        val url = prepare.optString("url", "")

                        withContext(Dispatchers.Main) {
                            when (prepAction) {
                                "BELL" -> onBell(
                                    BellEvent(
                                        soundFile = url.substringAfterLast("/"),
                                        playAtMs = playAtMs,
                                        durationMs = durationMs,
                                        snapActive = prepSnapActive,
                                        unmutedDeviceIds = unmuted,
                                    )
                                )

                                "TTS" -> onTts(
                                    TtsEvent(
                                        text = prepare.optString("text", ""),
                                        title = prepare.optString("title", "Hangos közlemény"),
                                        playAtMs = playAtMs,
                                        durationMs = durationMs,
                                        snapActive = prepSnapActive,
                                        unmutedDeviceIds = unmuted,
                                    )
                                )

                                "PLAY_URL" -> onRadio(
                                    RadioEvent(
                                        title = prepare.optString("title", "Iskolarádió"),
                                        snapActive = prepSnapActive,
                                        unmutedDeviceIds = unmuted,
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Azonnali broadcast ────────────────────────────────────────
                action.isNotEmpty() && phase.isEmpty() -> {
                    val durationMs = if (json.has("durationMs")) json.getLong("durationMs") else null
                    val unmuted = jsonStringList(json, "unmutedDeviceIds")

                    Log.d(
                        TAG,
                        "Broadcast: action=$action snap=$snapActive dur=$durationMs unmuted=${unmuted.size}"
                    )

                    scope.launch(Dispatchers.Main) {
                        when (action) {
                            "BELL" -> onBell(
                                BellEvent(
                                    soundFile = json.optString("url", "").substringAfterLast("/"),
                                    playAtMs = System.currentTimeMillis(),
                                    durationMs = durationMs,
                                    snapActive = snapActive,
                                    unmutedDeviceIds = unmuted,
                                )
                            )

                            "TTS" -> onTts(
                                TtsEvent(
                                    text = json.optString("text", ""),
                                    title = json.optString("title", "Hangos közlemény"),
                                    playAtMs = System.currentTimeMillis(),
                                    durationMs = durationMs,
                                    snapActive = snapActive,
                                    unmutedDeviceIds = unmuted,
                                )
                            )

                            "PLAY_URL" -> onRadio(
                                RadioEvent(
                                    title = json.optString("title", "Iskolarádió"),
                                    snapActive = snapActive,
                                    unmutedDeviceIds = unmuted,
                                )
                            )

                            "STOP_PLAYBACK" -> onStop()

                            // Bell szinkron push – azonnal frissít.
                            "SYNC_BELLS" -> {
                                Log.d(TAG, "SYNC_BELLS push – bell refresh")
                                onSyncBells()
                            }

                            // Backend audio-mixer onSourceStart event –
                            // a HUD-ot frissítjük az aktuálisan szóló forrás
                            // info-jával. Erre nem indul új audio playback
                            // a kliens-side-on (a snap stream folyamatos), csak
                            // a UI-overlay-t állítjuk be – különösen fontos a
                            // forrás-csere + resume esetén (pl. TTS megszakítja
                            // a netrádiót, TTS lejár, RADIO resume → most már
                            // megjelenik a HUD a "Internetrádió" névvel).
                            "NOW_PLAYING_INFO" -> {
                                val jobType    = json.optString("jobType", "")
                                val title      = json.optString("title", "")
                                val sourceType = json.optString("sourceType", "")
                                Log.d(
                                    TAG,
                                    "NOW_PLAYING_INFO: $jobType '$title' (source=$sourceType)"
                                )
                                // FONTOS: NE onBell/onTts/onRadio-t hívjunk – azok a
                                // PREPARE/PLAY flow eseményei, célzás-listával jönnek.
                                // A NOW_PLAYING_INFO célzás nélküli broadcast (a backend
                                // source:start eventjén megy ki minden tenant-eszközre),
                                // ezért ha az `applyTargeting`-on át mennénk, az üres
                                // unmutedDeviceIds miatt mindig HUD-ot mutatna – akkor is,
                                // ha az eszköz nem célzott.
                                // Helyette külön callback (`onNowPlayingInfo`), és a
                                // PlayerService a snapClient.isLocalMuted() alapján
                                // dönt arról, hogy mutat-e HUD-ot.
                                onNowPlayingInfo(NowPlayingInfo(
                                    jobType    = jobType,
                                    title      = title,
                                    sourceType = sourceType,
                                    durationMs = durationMs,
                                ))
                            }
                        }
                    }
                }

                type == "HELLO" -> {
                    // Kliens-óra eltolódás kiszámolása a server-órához képest.
                    // serverClockOffsetMs = serverNowMs - localNow.
                    // Egy kis hálózati one-way latency-t (50-100ms) elhanyagolunk:
                    // a snap audio cross-sync úgyis pontosabb (TIME_SYNC alkalmas
                    // finomításra).
                    val serverNowMs = json.optLong("serverNowMs", 0L)
                    if (serverNowMs > 0) {
                        serverClockOffsetMs = serverNowMs - System.currentTimeMillis()
                        Log.d(TAG, "HELLO deviceId=${json.optString("deviceId")} clockOffsetMs=$serverClockOffsetMs")
                    } else {
                        Log.d(TAG, "HELLO deviceId=${json.optString("deviceId")} (no serverNowMs)")
                    }
                }

                type == "TIME_SYNC_RESPONSE" -> {
                    // Finomítás: TIME_SYNC kérés → response. A serverNow visszajött,
                    // és az RTT/2-t hozzáadjuk (one-way latency közelítés).
                    try {
                        val serverNowIso = json.optString("serverNow", "")
                        if (serverNowIso.isNotEmpty()) {
                            // ISO string parsing (lenient)
                            val serverNowMs = ISO_FORMAT.parse(serverNowIso)?.time ?: 0L
                            if (serverNowMs > 0) {
                                // Az RTT becsléséhez egy kliens-seq-t lehetne küldeni;
                                // itt a serverNow ~= now + one-way. Half-RTT korrekció
                                // nélkül is jobb mint az ős-offset.
                                serverClockOffsetMs = serverNowMs - System.currentTimeMillis()
                                Log.d(TAG, "TIME_SYNC clockOffsetMs=$serverClockOffsetMs")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "TIME_SYNC parse error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }
}