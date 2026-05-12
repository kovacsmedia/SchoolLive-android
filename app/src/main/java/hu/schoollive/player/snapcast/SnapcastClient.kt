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
import org.concentus.OpusDecoder
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

    // ── Codec állapot ─────────────────────────────────────────────────────
    //
    // A backend snapserver `codec=opus&bitrate=192&chunk_ms=20` beállítást
    // használ az atomstabil multiroom streamhez. A WireChunk payload Opus-
    // kódolt, és a kliensnek dekódolnia kell PCM-mé az AudioTrack lejátszás
    // előtt. A PCM fallback megmarad, ha a snapserver mégis PCM-mel küld.
    private enum class Codec { UNKNOWN, PCM, OPUS }

    @Volatile
    private var currentCodec: Codec = Codec.UNKNOWN

    private var opusChannelCount: Int = 2
    private var opusDecoder: OpusDecoder? = null

    // Az opus_decode kimenete int16 PCM samples; a max chunk 60 ms @ 48kHz
    // stereo = 5760 sample. 8192 biztonsági puffer.
    private val opusOutShorts = ShortArray(8192)

    // ── Volume / mute állapot ─────────────────────────────────────────────

    @Volatile
    private var userVolume: Int = 100

    @Volatile
    private var localMuted: Boolean = false

    @Volatile
    private var serverMuted: Boolean = false

    @Volatile
    private var serverVolume: Int = 100

    // A snap szerver oldali jitter buffer mélysége. A snapserver
    // ServerSettings üzenetben küldi minden klienseinek (alapérték: 1000 ms).
    // Az ESP, Linux és Android klienseknek UGYANAZT az értéket kell használnia
    // a playback latency-hez, különben szinkronizálva nem szólnak (multiroom).
    @Volatile
    private var serverBufferMs: Long = 1000L

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
        /*
         * ESP-szabványú viselkedés:
         * A snap szerver oldali mute/volume beállításokat IGNORÁLJUK. A
         * backend `applyTargetingToClients` ezeket úgy állítja be, hogy
         * minden kliens "muted=true volume=0", és csak a célzott eszközöket
         * unmutázza - de a snap szerver oldali server-settings broadcast a
         * lejátszás közben rajzol új resync-eket. A klienseink (ESP, Android,
         * Linux/Windows) önmaguk döntenek a hangerőről a saját userVolume +
         * localMute alapján.
         *
         * A serverMuted / serverVolume mezőket továbbra is olvassuk a logba,
         * de NEM applikáljuk az AudioTrack-re.
         */
        val effective = if (localMuted) 0 else userVolume

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

            val codecName = String(ByteArray(nameLen).also { bb.get(it) })
            Log.d(TAG, "Codec: $codecName")

            // Az ezt követő opus / pcm specifikus header méret prefix.
            // Mindkét codec-nél van egy `uint32 headerSize` mező, utána a
            // codec-specifikus header tartalom.
            when (codecName.lowercase()) {
                "opus" -> setupOpus(bb)
                "pcm"  -> setupPcm(bb)
                else -> {
                    Log.w(TAG, "Unsupported codec: $codecName")
                    return
                }
            }

            audioQueue.clear()
            initAudioTrack()
        } catch (e: Exception) {
            Log.w(TAG, "CodecHeader parse error: ${e.message}")
        }
    }

    /**
     * Opus codec header layout a snapcast szerver szerint (lásd:
     * CarlosDerSeher/snap_app `codec_header_received()` Opus ága):
     *
     *   uint32  codecDataSize  (= 12, az utána következő mezők összmérete)
     *   ── codec_data 12 byte ──
     *   uint32  [magic/padding ignored]    @ offset 0..3
     *   uint32  sample_rate                @ offset 4..7    (pl. 48000)
     *   uint16  bits_per_sample            @ offset 8..9    (16)
     *   uint16  channels                   @ offset 10..11  (2 = stereo)
     *
     * FONTOS: az első 4 byte-ot KI KELL HAGYNI. Az ESP referencia
     * implementáció `codecPayload + 4` offsetet használ.
     */
    private fun setupOpus(bb: ByteBuffer) {
        if (bb.remaining() < 4) return

        val codecDataSize = bb.int  // várt: 12
        if (codecDataSize < 12 || bb.remaining() < codecDataSize) {
            Log.w(TAG, "Opus codec_data too short: $codecDataSize, remaining=${bb.remaining()}")
            return
        }

        bb.int                                  // skip 4 byte magic/padding
        val rate = bb.int                       // sample rate
        val bits = bb.short.toInt() and 0xFFFF
        val ch   = bb.short.toInt() and 0xFFFF

        sampleRate = rate
        opusChannelCount = ch
        channels = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        encoding = AudioFormat.ENCODING_PCM_16BIT
        bytesPerMs = (rate * ch * 2) / 1000
        currentCodec = Codec.OPUS

        // Új Opus decoder ehhez a stream-konfigurációhoz.
        opusDecoder = OpusDecoder(rate, ch)

        Log.d(TAG, "Opus: ${ch}ch ${bits}bit ${rate}Hz → $bytesPerMs bytes/ms PCM (decoder ready)")
    }

    /**
     * PCM codec header (raw uncompressed, fallback):
     * A WAV-like RIFF fmt chunk-tartalom van benne.
     */
    private fun setupPcm(bb: ByteBuffer) {
        if (bb.remaining() < 16) return

        val headerSize = bb.int
        if (headerSize < 28 || bb.remaining() < 28) return

        bb.int; bb.int; bb.int; bb.int; bb.int
        bb.short

        val ch = bb.short.toInt() and 0xFFFF
        val rate = bb.int
        bb.int
        bb.short

        val bits = bb.short.toInt() and 0xFFFF

        sampleRate = rate
        channels = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        encoding = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
        bytesPerMs = (rate * ch * if (bits == 16) 2 else 1) / 1000
        currentCodec = Codec.PCM
        opusDecoder = null

        Log.d(TAG, "PCM: ${ch}ch ${bits}bit ${rate}Hz → $bytesPerMs bytes/ms")
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
            val encSize = bb.int  // OPUS-nál tömörített, PCM-nél a PCM méret

            if (encSize <= 0) return

            if (payload.size < 12 + encSize) {
                Log.w(
                    TAG,
                    "WireChunk too short: payload=${payload.size}, declaredSize=$encSize"
                )
                return
            }

            val serverTimestampMs = sec * 1000L + us / 1000L
            val pcm: ByteArray = when (currentCodec) {
                Codec.OPUS -> decodeOpusToPcm(payload, 12, encSize) ?: return
                Codec.PCM  -> payload.copyOfRange(12, 12 + encSize)
                else -> {
                    // Codec header még nem érkezett, eldobjuk a chunk-ot.
                    return
                }
            }

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
     * Egy Opus packet dekódolása PCM-re.
     *
     * A Concentus `OpusDecoder.decode()` int16[] kimenetre dolgozik, amit
     * little-endian byte arrayre konvertálunk az AudioTrack számára.
     *
     * @return a dekódolt PCM bytes, vagy null hibára (a chunk eldobódik).
     */
    private fun decodeOpusToPcm(src: ByteArray, srcOffset: Int, srcLen: Int): ByteArray? {
        val decoder = opusDecoder ?: return null

        return try {
            val samplesPerChannel = decoder.decode(
                src, srcOffset, srcLen,
                opusOutShorts, 0, opusOutShorts.size / opusChannelCount,
                false
            )
            if (samplesPerChannel <= 0) return null

            val totalSamples = samplesPerChannel * opusChannelCount
            val out = ByteArray(totalSamples * 2)
            val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until totalSamples) {
                outBuf.putShort(opusOutShorts[i])
            }

            out
        } catch (e: Exception) {
            Log.w(TAG, "Opus decode error: ${e.message}")
            null
        }
    }

    /**
     * Soft sync lejátszás.
     *
     * A kliens induláskor a szerveridőhöz igazítja a megszólalást,
     * de utána nem dobálja a chunkokat.
     *
     * Így:
     * - nem lesz hiányos a hang;
     * - megmarad az indulási szinkron;
     * - a kisebb driftet az AudioTrack folyamatos pufferelése elfedi.
     */
    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        // A targetLatencyMs a snap szerver oldali jitter buffer mélysége
        // (serverBufferMs, ami a server_settings üzenetből frissül).
        // Multiroom konzisztencia: minden kliens UGYANAZT az értéket használja.
        val initialPrebufferChunks = 10 // kb. 200 ms, ha 20 ms/chunk

        var synced = false

        while (running) {
            val track = audioTrack

            if (track == null || !isConnected) {
                synced = false
                delay(20)
                continue
            }

            if (!serverOffsetKnown) {
                synced = false
                delay(10)
                continue
            }

            // Induláskor várunk egy kis puffert, hogy ne darabosan kezdjen.
            if (!synced && audioQueue.size < initialPrebufferChunks) {
                delay(10)
                continue
            }

            val chunk = audioQueue.poll()

            if (chunk == null) {
                delay(2)
                continue
            }

            if (!synced) {
                val bufferMs = serverBufferMs
                val localServerNowMs = System.currentTimeMillis() + serverOffsetMs
                val desiredStartMs = chunk.serverTimestampMs + bufferMs
                val waitMs = desiredStartMs - localServerNowMs

                if (waitMs > 0) {
                    Log.d(TAG, "Initial sync wait: ${waitMs}ms (bufferMs=$bufferMs)")
                    delay(waitMs.coerceAtMost(bufferMs))
                } else {
                    Log.d(TAG, "Initial sync late by ${-waitMs}ms (bufferMs=$bufferMs), playing without drop")
                }

                synced = true
            }

            // Itt szándékosan nem dobunk chunkot.
            // A korábbi late-drop logika okozta a hiányos hangot.
            val written = track.write(chunk.pcm, 0, chunk.pcm.size)

            if (written < 0) {
                Log.w(TAG, "AudioTrack write error: $written")
                synced = false
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
            val bufferMs = json.optInt("bufferMs", 1000).toLong().coerceIn(200L, 5000L)

            // A célzott némítást továbbra is a localMuted logika kezeli.
            // A snapserver muted flagjét és serverVolume-t nem alkalmazzuk
            // (lásd applyEffectiveVolume() ESP-szabványú viselkedés).
            serverVolume = volume

            // A bufferMs viszont KRITIKUS a multiroom szinkronhoz: az ESP,
            // Android és Linux klienseknek ugyanazt a buffer-mélységet kell
            // használnia. Ha az Android 1200 ms-mal játszott, az ESP 1000-rel,
            // 200 ms eltolódás van a két forrás közt.
            serverBufferMs = bufferMs

            applyEffectiveVolume()

            Log.d(TAG, "ServerSettings: vol=$volume muted=$muted bufferMs=$bufferMs (vol/muted ignored, bufferMs applied)")
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