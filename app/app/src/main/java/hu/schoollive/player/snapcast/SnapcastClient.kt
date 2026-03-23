package hu.schoollive.player.snapcast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SnapcastClient"
private const val RING_BUFFER_SIZE = 384 * 1024   // 384 KB ≈ 2 sec @ 48k/16bit/mono
private const val RECONNECT_DELAY_MS = 3_000L

// Snapcast v2 message types
private const val TYPE_CODEC_HEADER   = 1
private const val TYPE_WIRE_CHUNK     = 2
private const val TYPE_SERVER_SETTINGS = 3
private const val TYPE_TIME           = 4
private const val TYPE_HELLO          = 5

/**
 * Native Snapcast v2 TCP client.
 *
 * Usage:
 *   val client = SnapcastClient(host, port, onConnected, onDisconnected)
 *   client.start()    // launches coroutine loop
 *   client.stop()
 */
class SnapcastClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile private var running = false
    @Volatile var isConnected = false
        private set

    // Audio
    private var audioTrack: AudioTrack? = null
    private var sampleRate = 48000
    private var channels = AudioFormat.CHANNEL_OUT_MONO
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    // Ring buffer
    private val ring = ByteArray(RING_BUFFER_SIZE)
    private var ringWrite = 0
    private var ringRead = 0
    private var ringAvail = 0

    private val ringLock = Object()

    fun start() {
        running = true
        job = scope.launch { connectLoop() }
        // Separate coroutine feeds AudioTrack from ring buffer
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
        val gain = volumePercent / 100f
        audioTrack?.setVolume(gain)
    }

    // ── Connection loop ──────────────────────────────────────────────────────

    private suspend fun connectLoop() = withContext(Dispatchers.IO) {
        while (running) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                val socket = Socket(host, port)
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                sendHello(output)
                isConnected = true
                withContext(Dispatchers.Main) { onConnected() }

                readLoop(input)

            } catch (e: Exception) {
                if (running) Log.w(TAG, "Connection lost: ${e.message}")
            } finally {
                isConnected = false
                withContext(Dispatchers.Main) { onDisconnected() }
                if (running) delay(RECONNECT_DELAY_MS)
            }
        }
    }

    // ── Hello handshake ───────────────────────────────────────────────────────

    private fun sendHello(out: OutputStream) {
        val json = JSONObject().apply {
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

        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        // Header: type(2) + id(2) + refId(2) + sent_sec(4) + sent_usec(4) + recv_sec(4) + recv_usec(4) + size(4) = 26 bytes
        // Payload prefix: 4-byte LE json length + json
        val payload = ByteArray(4 + jsonBytes.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(jsonBytes.size)
        jsonBytes.copyInto(payload, 4)

        val header = buildHeader(TYPE_HELLO, payload.size)
        out.write(header)
        out.write(payload)
        out.flush()
        Log.d(TAG, "Hello sent (${jsonBytes.length} bytes json)")
    }

    private fun buildHeader(type: Int, payloadSize: Int): ByteArray {
        val buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(type.toShort())     // type
        buf.putShort(0)                   // id
        buf.putShort(0)                   // ref id
        buf.putInt(0)                     // sent sec
        buf.putInt(0)                     // sent usec
        buf.putInt(0)                     // recv sec
        buf.putInt(0)                     // recv usec
        buf.putInt(payloadSize)
        return buf.array()
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop(input: InputStream) {
        val headerBuf = ByteArray(26)
        while (running) {
            readFully(input, headerBuf) ?: break
            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.short.toInt()
            bb.short   // id
            bb.short   // refId
            bb.int     // sent sec
            bb.int     // sent usec
            val recvSec = bb.int
            val recvUsec = bb.int
            val size = bb.int

            val payload = ByteArray(size)
            if (size > 0) readFully(input, payload) ?: break

            when (type) {
                TYPE_CODEC_HEADER    -> handleCodecHeader(payload)
                TYPE_WIRE_CHUNK      -> handleWireChunk(payload, recvSec, recvUsec)
                TYPE_SERVER_SETTINGS -> handleServerSettings(payload)
                TYPE_TIME            -> { /* ignore */ }
                else                 -> Log.v(TAG, "Unknown msg type $type, size=$size")
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
        // Payload: 4-byte codec name length + codec name + 4-byte header length + header data
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val nameLen = bb.int
        val nameBytes = ByteArray(nameLen)
        bb.get(nameBytes)
        val codec = String(nameBytes)
        Log.d(TAG, "Codec: $codec")

        // For PCM, parse the format from the remaining header
        // Header is typically: channels(2) + bits(2) + rate(4)
        if (codec.lowercase() == "pcm" && bb.remaining() >= 8) {
            bb.int  // header size field
            val ch = bb.short.toInt()
            val bits = bb.short.toInt()
            val rate = bb.int

            sampleRate = rate
            channels = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            encoding = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            Log.d(TAG, "PCM format: ${ch}ch ${bits}bit ${rate}Hz")
        }

        initAudioTrack()
    }

    private fun handleWireChunk(payload: ByteArray, sec: Int, usec: Int) {
        // Payload: timestamp(8) + pcm data
        if (payload.size <= 8) return
        val pcm = payload.copyOfRange(8, payload.size)
        pushToRing(pcm)
    }

    private fun handleServerSettings(payload: ByteArray) {
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val jsonLen = bb.int
            val jsonBytes = ByteArray(jsonLen)
            bb.get(jsonBytes)
            val json = JSONObject(String(jsonBytes))
            val muted = json.optBoolean("muted", false)
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
                val space = RING_BUFFER_SIZE - ringAvail
                if (space == 0) {
                    // Overflow: drop oldest data
                    val drop = minOf(data.size - written, RING_BUFFER_SIZE / 4)
                    ringRead = (ringRead + drop) % RING_BUFFER_SIZE
                    ringAvail -= drop
                }
                val canWrite = minOf(data.size - written, RING_BUFFER_SIZE - ringWrite)
                data.copyInto(ring, ringWrite, written, written + canWrite)
                ringWrite = (ringWrite + canWrite) % RING_BUFFER_SIZE
                ringAvail += canWrite
                written += canWrite
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
                ringRead = (ringRead + canRead) % RING_BUFFER_SIZE
                ringAvail -= canRead
                read += canRead
            }
            return read
        }
    }

    // ── AudioTrack ────────────────────────────────────────────────────────────

    private fun initAudioTrack() {
        audioTrack?.stop()
        audioTrack?.release()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)
        val bufSize = minBuf * 4  // generous buffer

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(channels)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        Log.d(TAG, "AudioTrack initialised: ${sampleRate}Hz bufSize=$bufSize")
    }

    // ── Playback loop (feeds AudioTrack from ring buffer) ─────────────────────

    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        val chunk = ByteArray(4096)
        while (running) {
            val track = audioTrack
            if (track == null || !isConnected) {
                delay(50)
                continue
            }
            val n = popFromRing(chunk, chunk.size)
            if (n > 0) {
                track.write(chunk, 0, n)
            }
        }
    }
}
