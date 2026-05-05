package hu.schoollive.player.snapcast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

private const val TAG = "SnapcastClient"
private const val RECONNECT_DELAY_MS = 3_000L

private const val TYPE_CODEC_HEADER    = 1
private const val TYPE_WIRE_CHUNK      = 2
private const val TYPE_SERVER_SETTINGS = 3
private const val TYPE_TIME            = 4
private const val TYPE_HELLO           = 5

private data class AudioChunk(val pcm: ByteArray, val serverTimestampMs: Long)

class SnapcastClient(
    private val host:           String,
    private val port:           Int,
    /** A backend device.id, amelyet a snap HELLO `ID` mezőben elküldünk.
     *  Ez teszi lehetővé, hogy a backend JSON-RPC-vel célozza ezt a klienst
     *  (Client.SetVolume) per-device mute/unmute műveletekhez.
     *  Ha üres, fallback a régi "schoollive-android" konstansra. */
    private val deviceId:       String = "",
    private val onConnected:    () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    // Snap LED pulse trigger – minden audio chunk beérkezésekor hívódik.
    // A MainActivity ebből pulzáltatja az indicatorSnap LED-et.
    private val onActivity:     () -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var running = false
    @Volatile var isConnected = false; private set

    private var audioTrack: AudioTrack? = null
    private var sampleRate = 48000
    private var channels   = AudioFormat.CHANNEL_OUT_STEREO
    private var encoding   = AudioFormat.ENCODING_PCM_16BIT
    private var bytesPerMs = 192

    // ── Két-rétegű volume vezérlés ────────────────────────────────────────
    // user-volume: a kliens felhasználói gombokkal állítja (0-100)
    // local-mute: a backend WS protokoll fordított targeting fallback-je
    //             (ha a saját deviceId ISMERT és NEM szerepel az
    //             unmutedDeviceIds listában)
    // server-mute: a snapserver JSON-RPC Client.SetVolume-jából érkezik
    //              (handleServerSettings)
    // Effektív volume = (localMuted || serverMuted) ? 0 : userVolume.
    //
    // FONTOS: mindkét mute alapértelmezése FALSE (hangos, backward compat).
    // A mute csak akkor aktiválódik, ha a targeting logika BIZONYOSAN tudja,
    // hogy ez az eszköz nincs megcélozva. Ha nincs deviceId, vagy ha a
    // backend nem küld unmutedDeviceIds listát, az eszköz szól.
    // Ez az iskolai rendszerben biztonságosabb fallback, mint a némaság.
    @Volatile private var userVolume:  Int     = 100
    @Volatile private var localMuted:  Boolean = false  // false = szól alapból
    @Volatile private var serverMuted: Boolean = false  // false = szól alapból
    @Volatile private var serverVolume:Int     = 100

    @Volatile private var serverOffsetMs:    Long    = 0L
    @Volatile private var serverOffsetKnown: Boolean = false
    @Volatile private var timeSentLocalMs:   Long    = 0L

    private val TARGET_BUFFER_MS    = 1000L
    private val STALE_THRESHOLD_MS  = 1000L

    private val audioQueue = ArrayBlockingQueue<AudioChunk>(200)

    fun start() {
        running = true
        scope.launch { connectLoop() }
        scope.launch { playbackLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        audioQueue.clear()
        releaseAudioTrack()
    }

    /** Felhasználói hangerő (0-100). Layereződik a mute logikával. */
    fun setVolume(volumePercent: Int) {
        userVolume = volumePercent.coerceIn(0, 100)
        applyEffectiveVolume()
    }

    /** Backend-vezérelt lokális mute (WS protokoll fordított targeting fallback).
     *  true → némítva (akár a user-volume is); false → user-volume érvényes. */
    fun setLocalMute(muted: Boolean) {
        if (localMuted == muted) return
        localMuted = muted
        applyEffectiveVolume()
        Log.d(TAG, "localMute=$muted (effective vol applied)")
    }

    private fun applyEffectiveVolume() {
        val muted = localMuted || serverMuted
        val effective = if (muted) 0 else minOf(userVolume, serverVolume)
        audioTrack?.setVolume(effective / 100f)
    }

    private suspend fun connectLoop() = withContext(Dispatchers.IO) {
        while (running) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                val socket = Socket(host, port)
                socket.tcpNoDelay = true
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                serverOffsetKnown = false
                serverOffsetMs    = 0L

                sendHello(out)
                isConnected = true
                withContext(Dispatchers.Main) { onConnected() }

                delay(200)
                sendTimeRequest(out)
                readLoop(inp, out)

            } catch (e: Exception) {
                if (running) Log.w(TAG, "Connection lost: ${e.message}")
            } finally {
                isConnected       = false
                serverOffsetKnown = false
                audioQueue.clear()
                withContext(Dispatchers.Main) { onDisconnected() }
                if (running) delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun sendHello(out: OutputStream) {
        // A backend snapcast-rpc.ts modulja a snap kliens `ID`, `config.name`
        // vagy `host.name` mezőjét veti össze a backend device.id-jával,
        // hogy célozni tudja a JSON-RPC mute/unmute műveletekkel. Ezért
        // mindhárom mezőbe a deviceId-t tesszük (ha van).
        val effectiveId = if (deviceId.isNotEmpty()) deviceId else "schoollive-android-unknown"
        val jsonStr   = JSONObject().apply {
            put("MAC",                       "00:00:00:00:00:00")
            put("HostName",                  effectiveId)
            put("Version",                   "0.26.0")
            put("ClientName",                effectiveId)
            put("OS",                        "Android")
            put("Arch",                      "arm")
            put("Instance",                  1)
            put("ID",                        effectiveId)
            put("SnapStreamProtocolVersion", 2)
        }.toString()
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val payload   = ByteArray(4 + jsonBytes.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(jsonBytes.size)
        jsonBytes.copyInto(payload, 4)
        out.write(buildHeader(TYPE_HELLO, payload.size))
        out.write(payload)
        out.flush()
        Log.d(TAG, "Hello sent")
    }

    private fun sendTimeRequest(out: OutputStream) {
        timeSentLocalMs = System.currentTimeMillis()
        val nowUs   = timeSentLocalMs * 1000L
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((nowUs / 1_000_000L).toInt())
            .putInt((nowUs % 1_000_000L).toInt())
            .array()
        out.write(buildHeader(TYPE_TIME, payload.size))
        out.write(payload)
        out.flush()
    }

    private fun buildHeader(type: Int, payloadSize: Int): ByteArray =
        ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0); putShort(0)
            putInt(0);   putInt(0)
            putInt(0);   putInt(0)
            putInt(payloadSize)
        }.array()

    private fun readLoop(input: InputStream, output: OutputStream) {
        val headerBuf = ByteArray(26)
        while (running) {
            readFully(input, headerBuf) ?: break
            val bb      = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val type    = bb.short.toInt()
            bb.short; bb.short
            val hdrSec  = bb.int.toLong()
            val hdrUs   = bb.int.toLong()
            bb.int; bb.int
            val size    = bb.int

            val payload = ByteArray(size)
            if (size > 0 && readFully(input, payload) == null) break

            when (type) {
                TYPE_CODEC_HEADER    -> handleCodecHeader(payload)
                TYPE_WIRE_CHUNK      -> handleWireChunk(payload)
                TYPE_SERVER_SETTINGS -> handleServerSettings(payload)
                TYPE_TIME            -> handleTimeResponse(hdrSec, hdrUs)
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): ByteArray? {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return null
            offset += n
        }
        return buf
    }

    private fun handleTimeResponse(serverSec: Long, serverUs: Long) {
        val receivedLocalMs = System.currentTimeMillis()
        val rttMs           = receivedLocalMs - timeSentLocalMs
        val serverNowMs     = serverSec * 1000L + serverUs / 1000L
        serverOffsetMs      = serverNowMs - (timeSentLocalMs + rttMs / 2)
        serverOffsetKnown   = true
        Log.d(TAG, "TIME sync: offset=${serverOffsetMs}ms rtt=${rttMs}ms")
    }

    private fun handleCodecHeader(payload: ByteArray) {
        val bb      = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val nameLen = bb.int
        val codec   = String(ByteArray(nameLen).also { bb.get(it) })
        Log.d(TAG, "Codec: $codec")

        if (codec.lowercase() == "pcm" && bb.remaining() >= 16) {
            val headerSize = bb.int
            if (headerSize >= 28) {
                bb.int; bb.int; bb.int; bb.int; bb.int
                bb.short
                val ch   = bb.short.toInt() and 0xFFFF
                val rate = bb.int
                bb.int; bb.short
                val bits = bb.short.toInt() and 0xFFFF
                sampleRate = rate
                channels   = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                encoding   = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
                bytesPerMs = (rate * ch * (if (bits == 16) 2 else 1)) / 1000
                Log.d(TAG, "PCM: ${ch}ch ${bits}bit ${rate}Hz → $bytesPerMs bytes/ms")
            }
        }
        audioQueue.clear()
        initAudioTrack()
    }

    private fun handleWireChunk(payload: ByteArray) {
        if (payload.size <= 8) return
        val bb  = ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val sec = bb.int.toLong()
        val us  = bb.int.toLong()
        val serverTimestampMs = sec * 1000L + us / 1000L
        val pcm = payload.copyOfRange(8, payload.size)

        if (audioQueue.remainingCapacity() == 0) audioQueue.poll()
        audioQueue.offer(AudioChunk(pcm, serverTimestampMs))

        // Snap LED pulse – minden beérkező chunk aktivitást jelez
        onActivity()
    }

    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        while (running) {
            val track = audioTrack
            if (track == null || !isConnected) { delay(20); continue }
            if (!serverOffsetKnown) { delay(10); continue }

            val chunk = audioQueue.poll() ?: run { delay(5); return@run null } ?: continue

            val nowMs         = System.currentTimeMillis()
            val localPlayAtMs = chunk.serverTimestampMs - serverOffsetMs + TARGET_BUFFER_MS
            val diffMs        = localPlayAtMs - nowMs

            when {
                diffMs > 500                -> {
                    audioQueue.offer(chunk)
                    delay(minOf(diffMs - 200, 100))
                }
                diffMs < -STALE_THRESHOLD_MS -> {
                    Log.v(TAG, "Dropping stale chunk (${-diffMs}ms late)")
                }
                diffMs in 0..500            -> {
                    if (diffMs > 0) delay(diffMs)
                    track.write(chunk.pcm, 0, chunk.pcm.size)
                }
                else                        -> {
                    track.write(chunk.pcm, 0, chunk.pcm.size)
                }
            }
        }
    }

    private fun handleServerSettings(payload: ByteArray) {
        try {
            val bb      = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLen = bb.int
            val json    = JSONObject(String(ByteArray(jsonLen).also { bb.get(it) }))
            val muted   = json.optBoolean("muted", false)
            val volume  = json.optInt("volume", 100).coerceIn(0, 100)
            // FONTOS: a snap szerver tárolja a per-kliens muted állapotot, és a
            // korábbi backend kód muteAll()-t hívott minden lejátszás után.
            // Ezért a snap szerver minden csatlakozáskor muted=true-t küld,
            // ami elnémítja az AudioTrack-et. A backendes RPC unmute nem mindig
            // fut le időben. Ezért a snap szerver oldali `muted` flaget szándékosan
            // FIGYELMEN KÍVÜL HAGYJUK – a hangerőt (volume %) alkalmazzuk, de a
            // mute-ot NEM. A némutatást kizárólag a WS targetingből érkező
            // localMuted logika végzi.
            //
            // serverMuted = muted   ← szándékosan ki van kommentelve
            serverVolume = volume
            applyEffectiveVolume()
            Log.d(TAG, "ServerSettings: vol=$volume muted=$muted (muted flag ignored)")
        } catch (e: Exception) {
            Log.w(TAG, "ServerSettings parse error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun initAudioTrack() {
        releaseAudioTrack()
        val minBuf  = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
        val bufSize = maxOf(minBuf * 4, 16384)
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channels)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channels, encoding,
                bufSize, AudioTrack.MODE_STREAM)
        }
        audioTrack?.play()
        // Új AudioTrack alapból max volume-on van; alkalmazzuk az aktuális
        // (user × local-mute × server-mute) effektív szintet, hogy a kliens
        // ne kezdjen el rögtön hangosan szólni.
        applyEffectiveVolume()
        Log.d(TAG, "AudioTrack ready: ${sampleRate}Hz buf=$bufSize")
    }

    private fun releaseAudioTrack() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }
}