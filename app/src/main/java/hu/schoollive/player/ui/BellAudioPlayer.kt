package hu.schoollive.player.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "BellAudioPlayer"

/**
 * Helyi csengetés-lejátszó (offline út).
 *
 * Online a hang a snapcast streamből jön – ez az osztály CSAK akkor lép be,
 * amikor a backend nem elérhető, és a készüléknek a saját másolatából kell
 * megszólalnia.
 *
 * Hangerő: a rendszer média-csatornáján megy, tehát a készülék hangereje és a
 * távolról állított hangerő (SnapcastClient) egymástól függetlenek. Ez
 * szándékos: offline nincs snap-kliens, amire a távoli hangerő hatna.
 */
class BellAudioPlayer(private val ctx: Context) {

    /*
     * A MEDIAPLAYER-T A FŐSZÁLON KELL LÉTREHOZNI.
     *
     * A `MediaPlayer` a visszahívásait (onCompletion, onError) ANNAK a szálnak
     * a Looperén kézbesíti, amelyik létrehozta. A hívási lánc viszont
     * IO-szálon fut: BellManager tickje (Dispatchers.IO) → onOfflineBell →
     * play(). Ott NINCS Looper, tehát a visszahívások SOHA nem futnának le:
     *   • a MediaPlayer példány nem szabadulna fel (csengetésenként egy szivárgás),
     *   • a hangfókuszt sosem adnánk vissza (a háttérzene lehalkítva maradna).
     * A hang maga megszólalna, tehát egy gyors teszten ez nem tűnne fel.
     *
     * Ezért a lejátszás/leállítás MINDIG a főszálra kerül – így a hívó
     * bárhonnan hívhatja.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var player: MediaPlayer? = null

    /*
     * Külön foglaltság-jelző, nem a `MediaPlayer.isPlaying`.
     *
     * Az `isPlaying` az előkészítés (prepare) alatt még `false`, és a
     * csengetés-állapotgép EKKOR is rákérdezhet egy másik szálról – ilyenkor
     * tévesen szabadnak látná a lejátszót. A jelzőt a hívás legelején
     * állítjuk, és csak a tényleges befejezéskor/hibánál vesszük vissza.
     */
    @Volatile private var busy = false
    private var focusRequest: AudioFocusRequest? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private val audioManager: AudioManager
        get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)              // csengetés = riasztás jellegű
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Szól-e éppen valami. A csengetés-állapotgép ezt nézi, hogy ne indítson rá. */
    val isPlaying: Boolean get() = busy

    fun play(source: BellSoundStore.Source, onDone: (() -> Unit)? = null) = onMain {
        stopInternal()   // egy időben egy csengetés szólhat
        busy = true

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)

            when (source) {
                is BellSoundStore.Source.LocalFile -> {
                    Log.d(TAG, "Lejátszás (fájl): ${source.file.name}")
                    mp.setDataSource(source.file.absolutePath)
                }
                is BellSoundStore.Source.Bundled -> {
                    Log.d(TAG, "Lejátszás (gyári default)")
                    // Tömörített erőforrásnál null jönne vissza; az .mp3-at az
                    // aapt nem tömöríti, de ne NPE-zzünk, ha mégis.
                    val afd = ctx.resources.openRawResourceFd(source.resId)
                        ?: throw IllegalStateException("openRawResourceFd null")
                    afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
            }

            mp.setOnCompletionListener {
                Log.d(TAG, "Lejátszás vége")
                busy = false
                releaseFocus()
                it.release()
                if (player === it) player = null
                onDone?.invoke()
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "MediaPlayer hiba: what=$what extra=$extra")
                busy = false
                releaseFocus()
                p.release()
                if (player === p) player = null
                onDone?.invoke()
                true
            }

            requestFocus()
            mp.prepare()          // helyi fájl – gyors, nem kell async
            mp.start()
            player = mp
        } catch (e: Exception) {
            // A csengetés nem maradhat el némán ÉS észrevétlenül: naplózzuk,
            // hogy a hibajelzés-panelen is látszódjon a probléma.
            Log.e(TAG, "Csengetés lejátszása sikertelen: ${e.message}")
            busy = false
            releaseFocus()
            player = null
            onDone?.invoke()
        }
    }

    fun stop() = onMain { stopInternal() }

    private fun stopInternal() {
        busy = false
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        player = null
        releaseFocus()
    }

    // ── Hangfókusz ────────────────────────────────────────────────────────────
    // Rövid, tranziens fókusz: a háttérben szóló zenét lehalkítja, a csengetés
    // után visszaadja. Enélkül egyes készülékeken a csengetés némán menne el.

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hangfókusz kérése sikertelen: ${e.message}")
        }
    }

    private fun releaseFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) { }
    }
}
