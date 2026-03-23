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
    private val wsUrl: String,
    private val onPrepare: (codec: String, sampleRate: Int) -> Unit = { _, _ -> },
    private val onPlay: (playAtMs: Long) -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onMessage: (text: String) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isConnected = false
        private set

    // Csak egy aktív kapcsolat legyen egyszerre
    @Volatile private var activeWs: WebSocket? = null
    private var reconnectDelay = RECONNECT_BASE_MS

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

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() { scope.launch { connectLoop() } }

    fun stop() {
        activeWs?.close(1000, "Stopped")
        activeWs = null
        scope.cancel()
        isConnected = false
    }

    // ── Connection loop – csak akkor nyit új kapcsolatot ha nincs aktív ───────

    private suspend fun connectLoop() {
        while (scope.isActive) {
            if (!isConnected) {
                // Előző kapcsolat lezárása
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

    // ── WebSocket listener ────────────────────────────────────────────────────

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS connected")
            isConnected = true
            reconnectDelay = RECONNECT_BASE_MS
            scope.launch(Dispatchers.Main) { onConnected() }
        }
        override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            isConnected = false
            activeWs = null
            reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX_MS)
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed: $code")
            isConnected = false
            activeWs = null
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }
    }

    // ── Message handler ───────────────────────────────────────────────────────
    // A backend "phase" mezőt használ PREPARE/PLAY-hez, "type"-ot a többihez

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // "phase" mező: PREPARE, PLAY, STOP (SyncEngine üzenetek)
            val phase = json.optString("phase", "")
            if (phase.isNotEmpty()) {
                when (phase) {
                    "PREPARE" -> {
                        val action = json.optString("action", "")
                        val url    = json.optString("url", "")
                        val title  = json.optString("title", "")
                        Log.d(TAG, "PREPARE action=$action title=$title")
                        // TTS szöveg megjelenítése overlay-en
                        if (action == "TTS") {
                            val ttsText = json.optString("text", title)
                            if (ttsText.isNotEmpty()) {
                                scope.launch(Dispatchers.Main) { onMessage(ttsText) }
                            }
                        } else if (action == "PLAY_URL") {
                            // Rádió/audio lejátszás overlay
                            scope.launch(Dispatchers.Main) { onPlay(System.currentTimeMillis()) }
                        }
                        scope.launch(Dispatchers.Main) { onPrepare("pcm", 48000) }
                    }
                    "PLAY" -> {
                        val playAtMs = json.optLong("playAtMs", System.currentTimeMillis())
                        val diffMs   = playAtMs - System.currentTimeMillis()
                        Log.d(TAG, "PLAY at $playAtMs (in ${diffMs}ms)")
                        scope.launch(Dispatchers.Main) { onPlay(playAtMs) }
                    }
                    "STOP" -> {
                        Log.d(TAG, "STOP")
                        scope.launch(Dispatchers.Main) { onStop() }
                    }
                    else -> Log.v(TAG, "Unknown phase: $phase")
                }
                return
            }

            // "type" mező: HELLO, MESSAGE, egyéb
            when (json.optString("type", "")) {
                "HELLO"   -> Log.d(TAG, "Server HELLO received, deviceId=${json.optString("deviceId")}")
                "MESSAGE" -> {
                    val msg = json.optString("text", "")
                    if (msg.isNotEmpty()) scope.launch(Dispatchers.Main) { onMessage(msg) }
                }
                else      -> Log.v(TAG, "Unhandled: $text")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }
}