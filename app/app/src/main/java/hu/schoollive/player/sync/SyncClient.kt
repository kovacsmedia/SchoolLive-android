package hu.schoollive.player.sync

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "SyncClient"
private const val RECONNECT_BASE_MS = 1_000L
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

    private var ws: WebSocket? = null
    private var reconnectDelay = RECONNECT_BASE_MS

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun start() { scope.launch { connectLoop() } }

    fun stop() {
        ws?.close(1000, "Stopped")
        ws = null
        scope.cancel()
        isConnected = false
    }

    private suspend fun connectLoop() {
        while (scope.isActive) {
            openSocket()
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX_MS)
        }
    }

    private fun openSocket() {
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

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
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closed: $code")
            isConnected = false
            scope.launch(Dispatchers.Main) { onDisconnected() }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "PREPARE" -> {
                    val codec = json.optString("codec", "pcm")
                    val rate  = json.optInt("sampleRate", 48000)
                    scope.launch(Dispatchers.Main) { onPrepare(codec, rate) }
                }
                "PLAY" -> {
                    val playAtMs = json.optLong("playAtMs", System.currentTimeMillis())
                    Log.d(TAG, "PLAY in ${playAtMs - System.currentTimeMillis()}ms")
                    scope.launch(Dispatchers.Main) { onPlay(playAtMs) }
                }
                "STOP"    -> scope.launch(Dispatchers.Main) { onStop() }
                "MESSAGE" -> scope.launch(Dispatchers.Main) {
                    onMessage(json.optString("text", ""))
                }
                else -> Log.v(TAG, "Unknown: $text")
            }
        } catch (e: Exception) { Log.w(TAG, "Parse error: ${e.message}") }
    }
}
