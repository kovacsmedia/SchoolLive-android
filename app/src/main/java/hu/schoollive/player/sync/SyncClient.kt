package hu.schoollive.player.sync

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "SyncClient"
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS  = 30_000L

class SyncClient(
    private val wsUrl:          String,
    private val onPrepare:      (action: String, commandId: String) -> Unit = { _, _ -> },
    private val onPlay:         (playAtMs: Long, durationMs: Long?, action: String) -> Unit = { _, _, _ -> },
    private val onStop:         () -> Unit = {},
    private val onBell:         (soundFile: String, durationMs: Long?) -> Unit = { _, _ -> },
    private val onTts:          (text: String, durationMs: Long?) -> Unit = { _, _ -> },
    private val onRadio:        (title: String, snapActive: Boolean) -> Unit = { _, _ -> },
    private val onConnected:    () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isConnected = false
        private set

    @Volatile private var activeWs: WebSocket? = null
    private var reconnectDelay = RECONNECT_BASE_MS

    // Függőben lévő PREPARE állapotok
    private val pendingPrepare = mutableMapOf<String, JSONObject>()

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient = run {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    fun start() { scope.launch { connectLoop() } }

    fun stop() {
        activeWs?.close(1000, "Stopped")
        activeWs = null
        scope.cancel()
        isConnected = false
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            if (!isConnected) {
                activeWs?.close(1000, "Reconnecting")
                activeWs = null
                Log.d(TAG, "Connecting to $wsUrl")
                activeWs = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            }
            delay(reconnectDelay)
        }
    }

    private fun sendReadyAck(commandId: String) {
        val json = JSONObject().apply {
            put("type",      "READY_ACK")
            put("commandId", commandId)
            put("bufferMs",  0)
            put("readyAt",   java.time.Instant.now().toString())
        }
        activeWs?.send(json.toString())
        Log.d(TAG, "READY_ACK sent: $commandId")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS connected")
            isConnected = true
            reconnectDelay = RECONNECT_BASE_MS
            val syncReq = JSONObject().apply { put("type", "TIME_SYNC"); put("seq", System.currentTimeMillis()) }
            webSocket.send(syncReq.toString())
            scope.launch(Dispatchers.Main) { onConnected() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            isConnected = false; activeWs = null
            reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX_MS)
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed: $code")
            isConnected = false; activeWs = null
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json       = JSONObject(text)
            val phase      = json.optString("phase",  "")
            val type       = json.optString("type",   "")
            val action     = json.optString("action", "")
            val snapActive = json.optBoolean("snapcastActive", false)
            val durationMs = if (json.has("durationMs")) json.getLong("durationMs") else null

            when {
                // ── PREPARE ───────────────────────────────────────────────────
                phase == "PREPARE" -> {
                    val commandId = json.optString("commandId", "")
                    Log.d(TAG, "PREPARE action=$action commandId=$commandId snap=$snapActive")
                    pendingPrepare[commandId] = json

                    // Overlay előzetes megjelenítése (Snap hamarosan szólni fog)
                    when (action) {
                        "BELL" -> {
                            val sf = json.optString("url", "").substringAfterLast("/")
                            scope.launch(Dispatchers.Main) { onBell(sf, null) }
                        }
                        "TTS" -> {
                            val t = json.optString("text", json.optString("title", ""))
                            scope.launch(Dispatchers.Main) { onTts(t, null) }
                        }
                        "PLAY_URL" -> {
                            val title = json.optString("title", "Iskolarádió")
                            scope.launch(Dispatchers.Main) { onRadio(title, snapActive) }
                        }
                    }
                    sendReadyAck(commandId)
                    scope.launch(Dispatchers.Main) { onPrepare(action, commandId) }
                }

                // ── PLAY ──────────────────────────────────────────────────────
                phase == "PLAY" -> {
                    val commandId  = json.optString("commandId", "")
                    val playAtMs   = json.optLong("playAtMs", System.currentTimeMillis())
                    val dur        = if (json.has("durationMs")) json.getLong("durationMs") else null
                    val prepare    = pendingPrepare.remove(commandId)
                    val prepAction = prepare?.optString("action", "") ?: ""

                    val diffMs = playAtMs - System.currentTimeMillis()
                    Log.d(TAG, "PLAY action=$prepAction diffMs=$diffMs dur=${dur}ms")

                    if (diffMs < -10_000) {
                        Log.w(TAG, "PLAY stale (${-diffMs}ms) → skip"); return
                    }

                    val firePlay: () -> Unit = {
                        scope.launch(Dispatchers.Main) {
                            onPlay(playAtMs, dur, prepAction)
                            // durationMs frissítés az overlay-nek
                            if (dur != null) when (prepAction) {
                                "TTS"  -> onTts("", dur)
                                "BELL" -> onBell("", dur)
                            }
                        }
                    }

                    if (diffMs > 50) {
                        scope.launch { delay(diffMs); firePlay() }
                    } else {
                        firePlay()
                    }
                }

                // ── Azonnali broadcast (action van, phase nincs) ──────────────
                action.isNotEmpty() && phase.isEmpty() -> {
                    Log.d(TAG, "Immediate action=$action snap=$snapActive dur=$durationMs")
                    when (action) {
                        "BELL" -> {
                            val sf = json.optString("url", "").substringAfterLast("/")
                            scope.launch(Dispatchers.Main) { onBell(sf, durationMs) }
                        }
                        "TTS" -> {
                            val t = json.optString("text", json.optString("title", ""))
                            scope.launch(Dispatchers.Main) { onTts(t, durationMs) }
                        }
                        "PLAY_URL" -> {
                            val title = json.optString("title", "Iskolarádió")
                            scope.launch(Dispatchers.Main) { onRadio(title, snapActive) }
                        }
                        "STOP_PLAYBACK" -> scope.launch(Dispatchers.Main) { onStop() }
                        "SYNC_BELLS"    -> Log.d(TAG, "SYNC_BELLS – bell refresh kérve")
                    }
                }

                // ── HELLO ─────────────────────────────────────────────────────
                type == "HELLO" -> Log.d(TAG, "HELLO deviceId=${json.optString("deviceId")}")

                type == "TIME_SYNC_RESPONSE" -> Log.d(TAG, "TIME_SYNC ok")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }
}