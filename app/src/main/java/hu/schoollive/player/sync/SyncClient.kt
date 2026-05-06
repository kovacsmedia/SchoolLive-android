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

class SyncClient(
    private val wsUrl: String,
    private val onBell: (BellEvent) -> Unit = {},
    private val onTts: (TtsEvent) -> Unit = {},
    private val onRadio: (RadioEvent) -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSyncBells: () -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},

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

                    val diffMs = playAtMs - System.currentTimeMillis()

                    Log.d(
                        TAG,
                        "PLAY: action=$prepAction diffMs=${diffMs}ms dur=${durationMs}ms unmuted=${unmuted.size}"
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
                        }
                    }
                }

                type == "HELLO" -> {
                    Log.d(TAG, "HELLO deviceId=${json.optString("deviceId")}")
                }

                type == "TIME_SYNC_RESPONSE" -> {
                    Log.d(TAG, "TIME_SYNC ok")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }
}