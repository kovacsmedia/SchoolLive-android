package hu.schoollive.player.snapcast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

private const val TAG = "SnapcastClient"

private const val RECONNECT_DELAY_MS = 3_000L

private const val TYPE_CODEC_HEADER = 1
private const val TYPE_WIRE_CHUNK = 2
private const val TYPE_SERVER_SETTINGS = 3
private const val TYPE_TIME = 4
private const val TYPE_HELLO = 5

private data class AudioChunk(
    val pcm: ByteArray,
    val serverTimestampMs: Long
)

class SnapcastClient(
    private val host: String,
    private val port: Int,

    /**
     * Backend device.id.
     *
     * Ezt küldjük a snap HELLO `ID` mezőben.
     * Így tudja a backend célzottan némítani / engedélyezni az eszközt.
     */
    private val deviceId: String = "",

    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onActivity: () -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var running = false

    @Volatile
    var isConnected = false
        private set

    private var audioTrack: AudioTrack? = null

    private var sampleRate = 48000
    private var channels = AudioFormat.CHANNEL_OUT_STEREO
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var bytesPerMs = 192

    // ── Volume / mute állapot ─────────────────────────────────────────────

    @Volatile
    private var userVolume: Int = 100

    @Volatile
    private var localMuted: Boolean = false

    @Volatile
    private var serverMuted: Boolean = false

    @Volatile
    private var serverVolume: Int = 100

    // ── Snap time sync ────────────────────────────────────────────────────

    @Volatile
    private var serverOffsetMs: Long = 0L

    @Volatile
    private var serverOffsetKnown: Boolean = false

    @Volatile
    private var timeSentLocalMs: Long = 0L

    private val audioQueue = ArrayBlockingQueue<AudioChunk>(500)

    fun start() {
        if (running) return

        running = true

        scope.launch {
            connectLoop()
        }

        scope.launch {
            playbackLoop()
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        audioQueue.clear()
        releaseAudioTrack()
    }

    fun setVolume(volumePercent: Int) {
        userVolume = volumePercent.coerceIn(0, 100)
        applyEffectiveVolume()
    }

    fun setLocalMute(muted: Boolean) {
        if (localMuted == muted) return

        localMuted = muted
        applyEffectiveVolume()

        Log.d(TAG, "localMute=$muted")
    }

    private fun applyEffectiveVolume() {
        val muted = localMuted || serverMuted
        val effective = if (muted) 0 else minOf(userVolume, serverVolume)

        audioTrack?.setVolume(effective / 100f)
    }

    // ── Kapcsolódás ───────────────────────────────────────────────────────

    private suspend fun connectLoop() = withContext(Dispatchers.IO) {
        while (running) {
            var socket: Socket? = null

            try {
                Log.d(TAG, "Connecting to $host:$port")

                socket = Socket(host, port)
                socket.tcpNoDelay = true

                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                serverOffsetKnown = false
                serverOffsetMs = 0L
                audioQueue.clear()

                sendHello(out)

                isConnected = true

                withContext(Dispatchers.Main) {
                    onConnected()
                }

                delay(200)
                sendTimeRequest(out)

                readLoop(inp, out)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Connection lost: ${e.message}")
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }

                isConnected = false
                serverOffsetKnown = false
                audioQueue.clear()

                withContext(Dispatchers.Main) {
                    onDisconnected()
                }

                if (running) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun sendHello(out: OutputStream) {
        val effectiveId = if (deviceId.isNotEmpty()) {
            deviceId
        } else {
            "schoollive-android-unknown"
        }

        val jsonStr = JSONObject().apply {
            put("MAC", "00:00:00:00:00:00")
            put("HostName", effectiveId)
            put("Version", "0.26.0")
            put("ClientName", effectiveId)
            put("OS", "Android")
            put("Arch", "arm")
            put("Instance", 1)
            put("ID", effectiveId)
            put("SnapStreamProtocolVersion", 2)
        }.toString()

        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)

        val payload = ByteArray(4 + jsonBytes.size)

        ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(jsonBytes.size)

        jsonBytes.copyInto(payload, 4)

        out.write(buildHeader(TYPE_HELLO, payload.size))
        out.write(payload)
        out.flush()

        Log.d(TAG, "Hello sent: id=$effectiveId")
    }

    private fun sendTimeRequest(out: OutputStream) {
        timeSentLocalMs = System.currentTimeMillis()

        val nowUs = timeSentLocalMs * 1000L

        val payload = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt((nowUs / 1_000_000L).toInt())
            .putInt((nowUs % 1_000_000L).toInt())
            .array()

        out.write(buildHeader(TYPE_TIME, payload.size))
        out.write(payload)
        out.flush()
    }

    private fun buildHeader(type: Int, payloadSize: Int): ByteArray {
        return ByteBuffer.allocate(26)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putShort(type.toShort())
                putShort(0)
                putShort(0)

                putInt(0)
                putInt(0)

                putInt(0)
                putInt(0)

                putInt(payloadSize)
            }
            .array()
    }

    // ── Snap protokoll olvasás ────────────────────────────────────────────

    private fun readLoop(input: InputStream, output: OutputStream) {
        val headerBuf = ByteArray(26)

        while (running) {
            readFully(input, headerBuf) ?: break

            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)

            val type = bb.short.toInt()

            bb.short
            bb.short

            val hdrSec = bb.int.toLong()
            val hdrUs = bb.int.toLong()

            bb.int
            bb.int

            val size = bb.int

            val payload = ByteArray(size)

            if (size > 0 && readFully(input, payload) == null) {
                break
            }

            when (type) {
                TYPE_CODEC_HEADER -> handleCodecHeader(payload)
                TYPE_WIRE_CHUNK -> handleWireChunk(payload)
                TYPE_SERVER_SETTINGS -> handleServerSettings(payload)
                TYPE_TIME -> handleTimeResponse(hdrSec, hdrUs)
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
        val rttMs = receivedLocalMs - timeSentLocalMs

        val serverNowMs = serverSec * 1000L + serverUs / 1000L

        serverOffsetMs = serverNowMs - (timeSentLocalMs + rttMs / 2)
        serverOffsetKnown = true

        Log.d(TAG, "TIME sync: offset=${serverOffsetMs}ms rtt=${rttMs}ms")
    }

    private fun handleCodecHeader(payload: ByteArray) {
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val nameLen = bb.int

            if (nameLen <= 0 || nameLen > bb.remaining()) {
                Log.w(TAG, "Invalid codec name length: $nameLen")
                return
            }

            val codec = String(ByteArray(nameLen).also { bb.get(it) })

            Log.d(TAG, "Codec: $codec")

            if (codec.lowercase() == "pcm" && bb.remaining() >= 16) {
                val headerSize = bb.int

                if (headerSize >= 28 && bb.remaining() >= 28) {
                    bb.int
                    bb.int
                    bb.int
                    bb.int
                    bb.int

                    bb.short

                    val ch = bb.short.toInt() and 0xFFFF
                    val rate = bb.int

                    bb.int
                    bb.short

                    val bits = bb.short.toInt() and 0xFFFF

                    sampleRate = rate

                    channels = if (ch == 2) {
                        AudioFormat.CHANNEL_OUT_STEREO
                    } else {
                        AudioFormat.CHANNEL_OUT_MONO
                    }

                    encoding = if (bits == 16) {
                        AudioFormat.ENCODING_PCM_16BIT
                    } else {
                        AudioFormat.ENCODING_PCM_8BIT
                    }

                    bytesPerMs = (rate * ch * if (bits == 16) 2 else 1) / 1000

                    Log.d(TAG, "PCM: ${ch}ch ${bits}bit ${rate}Hz → $bytesPerMs bytes/ms")
                }
            }

            audioQueue.clear()
            initAudioTrack()
        } catch (e: Exception) {
            Log.w(TAG, "CodecHeader parse error: ${e.message}")
        }
    }

    /**
     * Fontos javítás:
     *
     * A Snapcast WireChunk payload szerkezete:
     *
     *   int32 sec
     *   int32 usec
     *   int32 pcmSize
     *   byte[] pcm
     *
     * Tehát a PCM nem a 8. bájttól indul, hanem a 12. bájttól.
     * Ha a size mezőt PCM-ként játsszuk le, az minden chunk elején kattanást,
     * zajt, 50 Hz-es hibát okoz.
     */
    private fun handleWireChunk(payload: ByteArray) {
        if (payload.size <= 12) return

        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val sec = bb.int.toLong()
            val us = bb.int.toLong()
            val pcmSize = bb.int

            if (pcmSize <= 0) return

            if (payload.size < 12 + pcmSize) {
                Log.w(
                    TAG,
                    "WireChunk too short: payload=${payload.size}, declaredPcmSize=$pcmSize"
                )
                return
            }

            val serverTimestampMs = sec * 1000L + us / 1000L
            val pcm = payload.copyOfRange(12, 12 + pcmSize)

            // Ha megtelik a queue, eldobjuk a legrégebbit.
            // Így nem nő végtelenül a késés.
            if (audioQueue.remainingCapacity() == 0) {
                audioQueue.poll()
            }

            audioQueue.offer(AudioChunk(pcm, serverTimestampMs))

            onActivity()
        } catch (e: Exception) {
            Log.w(TAG, "WireChunk parse error: ${e.message}")
        }
    }

    /**
     * Szinkronizált lejátszás.
     *
     * Nem a 20 ms-os chunkokat időzítjük vakon.
     * Ehelyett:
     *
     * - a Snapcast timestamp megmondja, hogy a chunk szerveridő szerint hová tartozik;
     * - az AudioTrack playbackHeadPosition megmondja, hogy az Android ténylegesen hol tart;
     * - ehhez igazítjuk a várakozást / dobást / resetet.
     */
    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        val targetLatencyMs = 300L

        // Ha ennél később érkezik egy chunk, inkább eldobjuk,
        // különben a késés folyamatosan nőne.
        val maxLateMs = 120L

        // Ha ennyire túl korainak tűnik, akkor valószínűleg sync reset kell.
        val maxEarlyMs = 800L

        var syncBaseServerMs: Long? = null
        var syncBasePlaybackFrames: Long = 0L

        fun playbackFrames64(track: AudioTrack): Long {
            // API 21 kompatibilis.
            // Rövid iskolai üzeneteknél nem várható 32 bites wraparound.
            return track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }

        fun playedMsSinceBase(track: AudioTrack): Long {
            val baseServer = syncBaseServerMs ?: return 0L

            val nowFrames = playbackFrames64(track)
            val frames = nowFrames - syncBasePlaybackFrames

            return if (frames <= 0) {
                0L
            } else {
                (frames * 1000L) / sampleRate
            }
        }

        while (running) {
            val track = audioTrack

            if (track == null || !isConnected) {
                syncBaseServerMs = null
                delay(20)
                continue
            }

            if (!serverOffsetKnown) {
                delay(10)
                continue
            }

            val chunk = audioQueue.poll()

            if (chunk == null) {
                delay(5)
                continue
            }

            val base = syncBaseServerMs

            if (base == null) {
                // Első chunk ehhez képest lesz a sync alap.
                syncBaseServerMs = chunk.serverTimestampMs
                syncBasePlaybackFrames = playbackFrames64(track)

                val localServerNowMs = System.currentTimeMillis() + serverOffsetMs
                val desiredStartMs = chunk.serverTimestampMs + targetLatencyMs
                val waitMs = desiredStartMs - localServerNowMs

                if (waitMs > 0) {
                    delay(waitMs.coerceAtMost(targetLatencyMs))
                }

                val written = track.write(chunk.pcm, 0, chunk.pcm.size)

                if (written < 0) {
                    Log.w(TAG, "AudioTrack write error: $written")
                    syncBaseServerMs = null
                }

                continue
            }

            val expectedServerPlaybackMs = base + playedMsSinceBase(track)
            val desiredChunkPlaybackMs = chunk.serverTimestampMs + targetLatencyMs
            val diffMs = desiredChunkPlaybackMs - expectedServerPlaybackMs

            when {
                diffMs < -maxLateMs -> {
                    Log.w(TAG, "Dropping late audio chunk: diff=${diffMs}ms")
                    continue
                }

                diffMs > maxEarlyMs -> {
                    Log.w(TAG, "Audio too early, resetting sync: diff=${diffMs}ms")
                    syncBaseServerMs = null
                    audioQueue.clear()
                    delay(50)
                    continue
                }

                diffMs > 20 -> {
                    delay(diffMs.coerceAtMost(80L))
                }
            }

            val written = track.write(chunk.pcm, 0, chunk.pcm.size)

            if (written < 0) {
                Log.w(TAG, "AudioTrack write error: $written")
                syncBaseServerMs = null
            }
        }
    }

    private fun handleServerSettings(payload: ByteArray) {
        try {
            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val jsonLen = bb.int

            if (jsonLen <= 0 || jsonLen > bb.remaining()) {
                Log.w(TAG, "Invalid ServerSettings json length: $jsonLen")
                return
            }

            val jsonBytes = ByteArray(jsonLen)
            bb.get(jsonBytes)

            val json = JSONObject(String(jsonBytes))

            val muted = json.optBoolean("muted", false)
            val volume = json.optInt("volume", 100).coerceIn(0, 100)

            // A célzott némítást továbbra is a localMuted logika kezeli.
            // A snapserver muted flagjét nem alkalmazzuk közvetlenül,
            // mert az ütközhet a saját célzási logikánkkal.
            serverVolume = volume
            applyEffectiveVolume()

            Log.d(TAG, "ServerSettings: vol=$volume muted=$muted ignored")
        } catch (e: Exception) {
            Log.w(TAG, "ServerSettings parse error: ${e.message}")
        }
    }

    // ── AudioTrack ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun initAudioTrack() {
        releaseAudioTrack()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channels, encoding)

        val bufSize = maxOf(
            minBuf * 4,
            bytesPerMs * 1000,
            32768
        )

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

        applyEffectiveVolume()

        Log.d(TAG, "AudioTrack ready: ${sampleRate}Hz buf=$bufSize")
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null
    }
}