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

private const val TAG = "SnapcastClient"
private const val RING_BUFFER_SIZE = 384 * 1024
private const val RECONNECT_DELAY_MS = 3_000L

private const val TYPE_CODEC_HEADER    = 1
private const val TYPE_WIRE_CHUNK      = 2
private const val TYPE_SERVER_SETTINGS = 3
private const val TYPE_TIME            = 4
private const val TYPE_HELLO           = 5

class SnapcastClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile private var running = false
    @Volatile var isConnected = false; private set

    private var audioTrack: AudioTrack? = null
    private var sampleRate = 48000
    private var channels = AudioFormat.CHANNEL_OUT_MONO
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    private val ring = ByteArray(RING_BUFFER_SIZE)
    private var ringWrite = 0
    private var ringRead = 0
    private var ringAvail = 0
    private val ringLock = Object()

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        running = true
        job = scope.launch { connectLoop() }
        scope.launch { playbackLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        scope.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isConnected = false
    }

    fun setVolume(volumePercent: Int) {
        audioTrack?.setVolume(volumePercent / 100f)
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private suspend fun connectLoop() = withContext(Dispatchers.IO) {
        while (running) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                val socket = Socket(host, port)
                socket.tcpNoDelay = true
                sendHello(socket.getOutputStream())
                isConnected = true
                withContext(Dispatchers.Main) { onConnected() }
                readLoop(socket.getInputStream())
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Connection lost: ${e.message}")
            } finally {
                isConnected = false
                withContext(Dispatchers.Main) { onDisconnected() }
                if (running) delay(RECONNECT_DELAY_MS)
            }
        }
    }

    // ── Hello ─────────────────────────────────────────────────────────────────

    private fun sendHello(out: OutputStream) {
        val jsonStr = JSONObject().apply {
            put("MAC", "00:00:00:00:00:00")
            put("HostName", "schoollive-android")
            put("Version", "0.26.0")
            put("ClientName", "SchoolLive Android")
            put("OS", "Android")
            put("Arch", "arm")
            put("Instance", 1)
            put("ID", "schoollive-android-1")
            put("SnapStreamProtocolVersion", 2)
        }.toString()

        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4 + jsonBytes.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(jsonBytes.size)
        jsonBytes.copyInto(payload, 4)

        out.write(buildHeader(TYPE_HELLO, payload.size))
        out.write(payload)
        out.flush()
        Log.d(TAG, "Hello sent (${jsonBytes.size} bytes)")
    }

    private fun buildHeader(type: Int, payloadSize: Int): ByteArray {
        return ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0); putShort(0)           // id, refId
            putInt(0); putInt(0)               // sent sec, usec
            putInt(0); putInt(0)               // recv sec, usec
            putInt(payloadSize)
        }.array()
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop(input: InputStream) {
        val headerBuf = ByteArray(26)
        while (running) {
            readFully(input, headerBuf) ?: break
            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.short.toInt()
            bb.short; bb.short                // id, refId
            bb.int; bb.int                    // sent sec, usec
            bb.int; bb.int                    // recv sec, usec
            val size = bb.int

            val payload = ByteArray(size)
            if (size > 0 && readFully(input, payload) == null) break

            when (type) {
                TYPE_CODEC_HEADER    -> handleCodecHeader(payload)
                TYPE_WIRE_CHUNK      -> handleWireChunk(payload)
                TYPE_SERVER_SETTINGS -> handleServerSettings(payload)
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

    // ── Message handlers ──────────────────────────────────────────────────────

    private fun handleCodecHeader(payload: ByteArray) {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val nameLen = bb.int
        val codec = String(ByteArray(nameLen).also { bb.get(it) })
        Log.d(TAG, "Codec: $codec")

        if (codec.lowercase() == "pcm" && bb.remaining() >= 16) {
            val headerSize = bb.int  // header data size
            // A Snapcast PCM codec header egy WAV RIFF/fmt chunk:
            // RIFF(4) + size(4) + WAVE(4) + fmt (4) + fmtSize(4) + audioFmt(2) + channels(2) + sampleRate(4) + ...
            if (headerSize >= 28) {
                bb.int   // "RIFF"
                bb.int   // file size
                bb.int   // "WAVE"
                bb.int   // "fmt "
                bb.int   // fmt chunk size (16)
                bb.short // audio format (1 = PCM)
                val ch   = bb.short.toInt() and 0xFFFF
                val rate = bb.int
                bb.int   // byte rate
                bb.short // block align
                val bits = bb.short.toInt() and 0xFFFF
                sampleRate = rate
                channels   = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                encoding   = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
                Log.d(TAG, "PCM (WAV header): ${ch}ch ${bits}bit ${rate}Hz")
            } else {
                // Egyszerű SampleFormat struktúra: rate(4) + bits(2) + channels(1)
                val rate = bb.int
                val bits = bb.short.toInt() and 0xFFFF
                val ch   = bb.get().toInt() and 0xFF
                sampleRate = rate
                channels   = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                encoding   = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
                Log.d(TAG, "PCM (simple): ${ch}ch ${bits}bit ${rate}Hz")
            }
        }
        initAudioTrack()
    }

    private fun handleWireChunk(payload: ByteArray) {
        if (payload.size <= 8) return
        pushToRing(payload.copyOfRange(8, payload.size))
    }

    private fun handleServerSettings(payload: ByteArray) {
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLen = bb.int
            val json = JSONObject(String(ByteArray(jsonLen).also { bb.get(it) }))
            val muted  = json.optBoolean("muted", false)
            val volume = json.optInt("volume", 100)
            if (!muted) setVolume(volume)
            Log.d(TAG, "ServerSettings: vol=$volume muted=$muted")
        } catch (e: Exception) {
            Log.w(TAG, "ServerSettings parse error: ${e.message}")
        }
    }

    // ── Ring buffer ───────────────────────────────────────────────────────────

    private fun pushToRing(data: ByteArray) {
        synchronized(ringLock) {
            var written = 0
            while (written < data.size) {
                if (RING_BUFFER_SIZE - ringAvail == 0) {
                    val drop = minOf(data.size - written, RING_BUFFER_SIZE / 4)
                    ringRead  = (ringRead + drop) % RING_BUFFER_SIZE
                    ringAvail -= drop
                }
                val canWrite = minOf(data.size - written, RING_BUFFER_SIZE - ringWrite)
                data.copyInto(ring, ringWrite, written, written + canWrite)
                ringWrite  = (ringWrite + canWrite) % RING_BUFFER_SIZE
                ringAvail += canWrite
                written   += canWrite
            }
            ringLock.notifyAll()
        }
    }

    private fun popFromRing(dest: ByteArray, len: Int): Int {
        synchronized(ringLock) {
            while (ringAvail < len && running) ringLock.wait(100)
            if (ringAvail == 0) return 0
            val toRead = minOf(len, ringAvail)
            var read = 0
            while (read < toRead) {
                val canRead = minOf(toRead - read, RING_BUFFER_SIZE - ringRead)
                ring.copyInto(dest, read, ringRead, ringRead + canRead)
                ringRead  = (ringRead + canRead) % RING_BUFFER_SIZE
                ringAvail -= canRead
                read      += canRead
            }
            return read
        }
    }

    // ── AudioTrack – API 21 kompatibilis ──────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun initAudioTrack() {
        audioTrack?.stop()
        audioTrack?.release()

        val minBuf  = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
        val bufSize = maxOf(minBuf * 4, 4096)

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+ – Builder
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channels)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            // API 21–22 – legacy constructor
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channels,
                encoding,
                bufSize,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack?.play()
        Log.d(TAG, "AudioTrack ready: ${sampleRate}Hz buf=$bufSize API=${Build.VERSION.SDK_INT}")
    }

    // ── Playback loop ─────────────────────────────────────────────────────────

    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        val chunk = ByteArray(4096)
        while (running) {
            val track = audioTrack
            if (track == null || !isConnected) { delay(50); continue }
            val n = popFromRing(chunk, chunk.size)
            if (n > 0) track.write(chunk, 0, n)
        }
    }
}