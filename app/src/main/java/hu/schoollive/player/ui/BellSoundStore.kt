package hu.schoollive.player.ui

import android.content.Context
import android.util.Log
import hu.schoollive.player.R
import hu.schoollive.player.api.ApiClient
import hu.schoollive.player.api.models.BellSound
import okhttp3.Request
import java.io.File

private const val TAG = "BellSoundStore"

/**
 * A csengetési hangfájlok HELYI másolata.
 *
 * MIÉRT KELL: online a hang a snapcast streamből jön, a kliens csak overlay-t
 * mutat. Offline viszont nincs stream – ilyenkor a készüléknek magának kell
 * lejátszania a fájlt. Eddig ez a lánc hiányzott az Android kliensből: az
 * offline tick megjelenítette a "csengetés" overlay-t, de HANG nem szólt.
 *
 * Az ESP32 ugyanezt csinálja LittleFS-re (BellManager `syncSounds`): letölti a
 * `/bells/sync` válasz `sounds[]` listáját, és méret alapján dönti el, kell-e
 * újratölteni.
 *
 * A GYÁRI DEFAULT a `res/raw`-ban van, tehát az APK-val érkezik. Így egy még
 * le nem töltött vagy a szerverről törölt hang sem okozhat CSENDET – ez a
 * rendszer alapszabálya.
 */
class BellSoundStore(private val ctx: Context) {

    private val dir: File by lazy {
        File(ctx.filesDir, "bells").apply { if (!exists()) mkdirs() }
    }

    // ── Szinkron ──────────────────────────────────────────────────────────────

    /**
     * A szerver listája alapján frissíti a helyi másolatokat.
     * Csak akkor tölt, ha a fájl hiányzik vagy a mérete eltér.
     */
    fun sync(sounds: List<BellSound>, serverUrl: String) {
        if (sounds.isEmpty()) return

        var ok = 0; var skip = 0; var fail = 0
        for (s in sounds) {
            val name = s.filename.trim()
            if (name.isEmpty() || s.url.isBlank()) continue

            val target = File(dir, name)
            if (target.exists() && s.sizeBytes > 0 && target.length() == s.sizeBytes.toLong()) {
                skip++
                continue
            }
            if (download(absoluteUrl(s.url, serverUrl), target)) ok++ else fail++
        }
        Log.d(TAG, "Hangok: $ok letöltve, $skip változatlan, $fail sikertelen")
    }

    /**
     * A backend RELATÍV utat küld (`/audio/bells/<tenant>/<fájl>`), mert a
     * hangok ugyanarról a hostról szolgálódnak ki. Abszolúttá kell tenni,
     * különben a `URL()` kivételt dob és a hang sosem töltődik le. Az ESP32
     * ugyanezt a prefixelést végzi (BellManager.cpp).
     */
    private fun absoluteUrl(url: String, serverUrl: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else serverUrl.trimEnd('/') + url

    /*
     * A letöltés UGYANAZZAL a HTTP-klienssel megy, mint a többi API-hívás.
     *
     * MIÉRT NEM sima `URL().openConnection()`: az app mindenhol trust-all
     * TLS-t használ (régi Android eszközök elavult tanúsítvány-tára miatt,
     * ld. ApiClient). Az alapértelmezett kliens ezeken a készülékeken
     * tanúsítvány-hibára futna – miközben minden MÁS működik. A tünet néma és
     * félrevezető lenne: offline mindig a gyári default szólna, pedig a hang
     * "fel van töltve".
     */
    private fun download(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, "${target.name}.part")
        return try {
            val req = Request.Builder().url(url).build()
            ApiClient.httpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Letöltés hiba (${resp.code}): $url")
                    return false
                }
                val body = resp.body ?: return false
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            // Csak a KÉSZ fájlt tesszük a helyére – egy megszakadt letöltés így
            // nem hagy csonka hangot, amit később lejátszanánk.
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "Letöltés kivétel: ${e.message}")
            tmp.delete()
            false
        }
    }

    // ── Feloldás ──────────────────────────────────────────────────────────────

    /**
     * A lejátszandó hang forrása.
     * `file` – letöltött másolat; `resId` – beépített gyári default.
     */
    sealed class Source {
        data class LocalFile(val file: File) : Source()
        data class Bundled(val resId: Int)   : Source()
    }

    /**
     * A kért hang feloldása. Ha nincs meg, a TÍPUS szerinti gyári defaultra
     * esünk vissza – csengetés nem maradhat el.
     */
    fun resolve(soundFile: String, type: String): Source {
        val name = soundFile.trim()
        if (name.isNotEmpty()) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) return Source.LocalFile(f)
            Log.w(TAG, "Hiányzó hangfájl: $name → gyári default")
        }
        return Source.Bundled(
            if (type.equals("SIGNAL", ignoreCase = true)) R.raw.default_signal
            else R.raw.default_main
        )
    }
}
