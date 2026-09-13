package hu.schoollive.player.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.schoollive.player.api.models.Bell
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.*
import java.util.Calendar

private const val TAG = "BellManager"

// ── Időzítés – az ESP32 firmware-rel AZONOS értékek ──────────────────────────
// Ld. SchoolLive-devices/src/BellManager.h. A két kliensnek ugyanúgy KELL
// döntenie, különben ugyanarra a csengetésre más-más viselkedést kapunk.
private const val TICK_INTERVAL_MS       = 1_000L   // volt 5 s – a 4 s-os türelmi időhöz durva
private const val BELL_LEAD_CHECK_S      = 60L      // ennyivel előbb kezdünk figyelni
private const val BELL_GRACE_S           = 4L       // ennyit várunk a PREPARE-re
private const val BELL_ONLINE_EVIDENCE_MS = 15_000L // friss PREPARE = él az online út
private const val BELL_CATCHUP_MAX_S     = 120L     // ennél régebbit már NEM pótolunk
private const val BELL_MATCH_WINDOW_S    = 45L      // PREPARE ↔ bejegyzés párosítás
private const val MIN_LOCAL_GAP_MS       = 15_000L  // két helyi csengetés közti minimum


class BellManager(private val ctx: Context) {

    // @Volatile: a listát a hálózati szál írja (updateBells), a tick coroutine
    // olvassa. Az immutable List csere önmagában atomi, a láthatóság viszont
    // nem garantált nélküle.
    @Volatile private var bells: List<Bell> = emptyList()

    // A napi állapotot (handled/armed/stateDay) KÉT szál módosítja: a WS
    // listener a `noteOnlineBell()`-en át, és a tick coroutine a
    // `checkSchedule()`-ön át. Sima MutableSet-ekkel ez versenyhelyzet
    // (ConcurrentModificationException), ezért közös záron mennek.
    private val stateLock = Any()
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Napi állapot – az ESP32 `_bellHandledBits` / `_bellArmedBits` párja ───
    //
    // handled: ezt a percet MA már elintéztük (helyben lejátszottuk, vagy a
    //          backend játszotta le, vagy túl régi a pótláshoz)
    // armed:   T-60-nál a backend NEM volt elérhető → a csengetés pillanatában
    //          azonnal, türelmi idő nélkül szólunk
    private val handled = mutableSetOf<String>()
    private val armed   = mutableSetOf<String>()
    private var stateDay = -1

    /*
     * TÖBB JELZÉS UGYANARRA A PERCRE.
     *
     * A `handled` percenként EGY kulcsot tárol, tehát önmagában csak egy
     * jelzést engedne percenként – a második némán kimaradna. Márpedig egy
     * időpontra több is beállítható (a szerkesztő rá is kérdez), és
     * mindegyiknek meg KELL szólalnia, egymás után.
     *
     * Ordinális megoldás, NEM a lista indexére építve (a lista szinkronkor
     * újraépül, egy elavult index elnyelhetne egy csengetést). Egy slot elég,
     * mert az azonos percre esők másodperceken belül mennek le. A perc kulcsa
     * CSAK akkor kerül a `handled`-be, ha az adott perc összes jelzése lement.
     *
     * Ugyanez a séma fut az ESP32-n (BellManager.h `_sameMinuteMin`).
     */
    private var sameMinuteKey  = ""
    private var sameMinuteDone = 0

    // Az utolsó BELL PREPARE ideje. Friss PREPARE = az online út él, tehát a
    // backend játssza le – mi nem szólunk bele (ez zárja ki a dupla csengetést).
    @Volatile private var lastOnlineBellMs = 0L

    // ── Offline mód feltétel ──────────────────────────────────────────────────
    //
    // OFFLINE = NINCS SNAP ÉS/VAGY NINCS WS — nem az, hogy mindkettő elesett.
    //
    // Korábban a feltétel `!snapOnline && !wsOnline` volt, azaz MINDKETTŐNEK
    // el kellett esnie ahhoz, hogy helyben csengessünk. Csakhogy a backend és
    // a snapserver KÜLÖN folyamat: ha csak az egyik hal meg, a hang SEHOL nem
    // szólal meg — a backend nem tudja lejátszani, mi meg "online"-nak hisszük
    // magunkat. Ugyanez a hiba volt az ESP32-n és a natív klienseken is, ott
    // már javítva (ld. main.cpp `setBackendReachable(ws && snap)`).
    //
    // Dupla csengetés emiatt nem lesz: a szerver által lejátszott csengetést a
    // `noteOnlineBell()` azonnal elintézettnek jelöli, az állapotgép pedig a
    // friss PREPARE-t önálló bizonyítékként is figyeli.
    @Volatile private var snapOnline = false
    @Volatile private var wsOnline   = false

    val isOfflineMode: Boolean get() = !(snapOnline && wsOnline)

    // Callback: offline bell lejátszandó. A TÍPUS is kell, mert `soundFile`
    // nélküli bejegyzésnél abból dől el, melyik gyári default szóljon.
    var onOfflineBell: ((soundFile: String, type: String) -> Unit)? = null

    // Szól-e éppen helyi lejátszás. A PlayerService köti be a tényleges
    // MediaPlayer-állapotra – enélkül csak időbeli becslésünk volt.
    var isLocalPlaybackActive: (() -> Boolean)? = null

    // Két helyi csengetés közti MINIMÁLIS térköz.
    //
    // Az ESP32-n erre az `audio.isBusy() || isInCooldown()` vizsgálat való, de
    // ott a lejátszó és az ütemező ugyanabban a komponensben van. Itt a hangot
    // a PlayerService-en túl játsszák le, befejezés-visszajelzés nélkül –
    // ezért nem állíthatunk "busy" flaget őszintén. Helyette időbeli térközt
    // tartunk: ez arra az esetre kell, amikor a szolgáltatás újraindulása után
    // TÖBB csengetés is a 120 másodperces pótlási ablakon belülre esik, és
    // különben másodpercenként egymásra indulnának.
    private var lastLocalPlayMs = 0L

    init {
        loadFromCache()
        startOfflineTick()
    }

    // ── Bell lista ────────────────────────────────────────────────────────────

    fun updateBells(newBells: List<Bell>) {
        bells = newBells
        PrefsUtil.setBellsJson(ctx, gson.toJson(newBells))
        Log.d(TAG, "Bells updated: ${bells.size}")
    }

    private fun loadFromCache() {
        val json = PrefsUtil.getBellsJson(ctx)
        if (json.isEmpty()) return
        try {
            val type = object : TypeToken<List<Bell>>() {}.type
            bells    = gson.fromJson(json, type)
            Log.d(TAG, "Bells loaded from cache: ${bells.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Bell cache parse error: ${e.message}")
        }
    }

    // ── Kapcsolat állapot ─────────────────────────────────────────────────────

    fun onSnapConnected()    { snapOnline = true;  logMode() }
    fun onSnapDisconnected() { snapOnline = false; logMode() }
    fun onWsConnected()      { wsOnline   = true;  logMode() }
    fun onWsDisconnected()   { wsOnline   = false; logMode() }

    private fun logMode() {
        Log.d(TAG, "Mód: ${if (isOfflineMode) "OFFLINE" else "ONLINE"} " +
                "(snap=$snapOnline ws=$wsOnline)")
    }

    // ── Szerver-oldali csengetés jelzése ──────────────────────────────────────
    //
    // A PlayerService hívja MINDEN BELL PREPARE-nél. Az időbélyeg mellett
    // AZONNAL elintézettnek is jelöljük az érintett bejegyzést.
    //
    // MIÉRT azonnal: az ESP32-n eleinte csak az időbélyeget tároltuk, és a
    // tick döntött róla később. Csakhogy a tick időnként percekig nem futott
    // le (lejátszás, cooldown, "playback quiet" ablak), mire pedig sorra
    // került, a 15 másodperces bizonyíték-ablak lejárt – és a backend által
    // MÁR elcsengetett jelzést az eszköz még egyszer lejátszotta helyben.
    // Az azonnali jelölés ettől független.
    fun noteOnlineBell() = synchronized(stateLock) {
        lastOnlineBellMs = System.currentTimeMillis()

        val now    = Calendar.getInstance()
        ensureDay(now)
        val nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600L +
                     now.get(Calendar.MINUTE) * 60L +
                     now.get(Calendar.SECOND)

        // A PREPARE néhány másodperccel a csengetés ELŐTT érkezik, ezért a
        // "most"-hoz LEGKÖZELEBBI, még el nem intézett bejegyzést azonosítjuk.
        var bestKey: String? = null
        var bestDist = BELL_MATCH_WINDOW_S + 1
        bells.forEachIndexed { idx, b ->
            val key = keyOf(b)
            if (key !in handled && !alreadyServed(idx, key)) {
                val dist = Math.abs(nowSec - (b.hour * 3600L + b.minute * 60L))
                if (dist <= BELL_MATCH_WINDOW_S && dist < bestDist) {
                    bestDist = dist
                    bestKey = key
                }
            }
        }
        val hit = bestKey ?: return@synchronized

        markEntryHandled(hit)
        armed.remove(hit)
        Log.d(TAG, "$hit – a backend játssza le (PREPARE), helyben nem csengetünk")
    }

    // Visszafelé kompatibilis név: a régi hívási helyek ezt használják.
    fun registerBell() = noteOnlineBell()

    // ── Csengetés-állapotgép ──────────────────────────────────────────────────
    //
    // Szó szerint az ESP32 `BellManager::checkSchedule()` logikája:
    //   • T-60 mp-től figyeljük az elérhetőséget ("felfegyverzés")
    //   • T-kor: ha friss BELL PREPARE van, a backend játssza – mi nem
    //   • ha T-60-kor nem volt elérhető, vagy most sem az → AZONNAL helyben
    //   • ha bizonytalan (elérhetőnek tűnik, de PREPARE nem jött) → türelmi
    //     idő, utána mégis helyben, hogy a csengetés ne maradjon el

    private fun startOfflineTick() {
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                try { checkSchedule() } catch (e: Exception) {
                    Log.w(TAG, "bell tick hiba: ${e.message}")
                }
            }
        }
    }

    private fun checkSchedule() {
        // A lejátszandó hangot zár alatt határozzuk meg, de a callbacket
        // ZÁRON KÍVÜL hívjuk – egy hosszabb hívó ne blokkolja a WS szálat.
        val toPlay = decideNextBell() ?: return
        onOfflineBell?.invoke(toPlay.first, toPlay.second)
    }

    /** (hangfájl, típus) vagy null, ha most nincs mit lejátszani. */
    private fun decideNextBell(): Pair<String, String>? = synchronized(stateLock) {
        if (bells.isEmpty()) return@synchronized null

        val now = Calendar.getInstance()
        ensureDay(now)

        val nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600L +
                     now.get(Calendar.MINUTE) * 60L +
                     now.get(Calendar.SECOND)

        val onlineEvidence =
            lastOnlineBellMs != 0L &&
            (System.currentTimeMillis() - lastOnlineBellMs) <= BELL_ONLINE_EVIDENCE_MS

        val reachable = !isOfflineMode

        // Sima indexelt ciklus (nem forEachIndexed): a `continue`/`break`
        // vezérlés lambdában nem használható, az index viszont kell az
        // ordinális átugráshoz.
        for (idx in bells.indices) {
            val b   = bells[idx]
            val key = keyOf(b)
            if (key in handled || alreadyServed(idx, key)) continue

            val dt = nowSec - (b.hour * 3600L + b.minute * 60L)

            // 1) Előzetes ellenőrzés T-60 mp-től, folyamatosan frissítve.
            if (dt in -BELL_LEAD_CHECK_S until 0L) {
                if (reachable) {
                    armed.remove(key)
                } else if (key !in armed) {
                    armed.add(key)
                    Log.d(TAG, "$key – a backend nem érhető el (${-dt} mp-cel előtte) → offline lejátszásra készülünk")
                }
                continue
            }
            if (dt < 0) continue

            // Felső korlát: egy délután induló kliens ne csengessen rá a
            // reggeli időpontokra.
            if (dt > BELL_CATCHUP_MAX_S) {
                handled.add(key); armed.remove(key); persistDone()
                continue
            }

            // 2) A csengetés pillanata (és utána).
            if (onlineEvidence) {
                handled.add(key); persistDone()
                continue
            }

            if (key !in armed && reachable && dt < BELL_GRACE_S) continue   // várunk a PREPARE-re

            // Ha épp szól valami, NEM teszünk rá másodikat – de a bejegyzést
            // sem jelöljük elintézettnek: a következő körökben újrapróbáljuk,
            // amíg a pótlási ablak tart.
            //
            // Elsődlegesen a TÉNYLEGES lejátszás-állapotot nézzük (MediaPlayer);
            // az időbeli térköz csak tartalék, ha a hívó nem kötött be semmit.
            if (isLocalPlaybackActive?.invoke() == true) return@synchronized null
            if (System.currentTimeMillis() - lastLocalPlayMs < MIN_LOCAL_GAP_MS) {
                return@synchronized null
            }

            // A gyári defaultra esést a BellSoundStore végzi (az APK-ba épített
            // hangokkal), ezért ide üres `soundFile` is mehet.
            val sound = b.soundFile

            val wasArmed = key in armed
            markEntryHandled(key)          // ordinális; a perc kulcsa csak ha mind kész
            armed.remove(key)
            lastLocalPlayMs = System.currentTimeMillis()
            Log.d(TAG, "OFFLINE csengetés @ $key  hang:$sound (snap=$snapOnline ws=$wsOnline armed=$wasArmed)")
            return@synchronized Pair(sound, b.type)   // egyszerre csak egyet
        }
        return@synchronized null
    }

    // ── Napi állapot + perzisztencia ──────────────────────────────────────────

    private fun keyOf(b: Bell) = "%02d:%02d".format(b.hour, b.minute)

    /** Hány bejegyzés esik erre a percre. */
    private fun entriesAtKey(key: String) = bells.count { keyOf(it) == key }

    /** Ez a bejegyzés hányadik a saját percén belül (0-tól). */
    private fun ordinalWithin(idx: Int, key: String) =
        bells.take(idx).count { keyOf(it) == key }

    /** Már lement ez a bejegyzés a saját percén belül? */
    private fun alreadyServed(idx: Int, key: String) =
        key == sameMinuteKey && ordinalWithin(idx, key) < sameMinuteDone

    /** Egy jelzés elintézve: ordinális léptetés, és ha a perc kész, a kulcs is. */
    private fun markEntryHandled(key: String) {
        if (sameMinuteKey != key) {
            sameMinuteKey  = key
            sameMinuteDone = 0
        }
        sameMinuteDone++

        val total = entriesAtKey(key)
        if (sameMinuteDone >= total) {
            handled.add(key)
            persistDone()
        } else {
            Log.d(TAG, "$key – $sameMinuteDone/$total jelzés kész, jön a következő")
        }
    }

    private fun ensureDay(now: Calendar) {
        val day = now.get(Calendar.DAY_OF_YEAR)
        if (stateDay == day) return

        val firstRun = stateDay == -1
        stateDay = day
        armed.clear()
        handled.clear()
        sameMinuteKey  = ""     // az ordinális slot is nullázódik (ld. fent)
        sameMinuteDone = 0

        if (firstRun && PrefsUtil.getBellDoneDay(ctx) == day) {
            // A szolgáltatás MA már futott: visszatöltjük, mit csengettünk el.
            // Enélkül egy újraindulás a pótlási ablakon belül megismételné.
            PrefsUtil.getBellDoneKeys(ctx)
                .split(',')
                .filter { it.isNotBlank() }
                .forEach { handled.add(it) }
            if (handled.isNotEmpty()) {
                Log.d(TAG, "Elcsengetve-állapot visszatöltve (${handled.size} bejegyzés)")
            }
        } else {
            persistDone()
        }
    }

    private fun persistDone() {
        PrefsUtil.setBellDone(ctx, stateDay, handled.joinToString(","))
    }

    // ── UI segéd ──────────────────────────────────────────────────────────────

    fun nextBellTime(): String? {
        val now        = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return bells
            .filter  { it.hour * 60 + it.minute > nowMinutes }
            .minByOrNull { it.hour * 60 + it.minute }
            ?.let { "%02d:%02d".format(it.hour, it.minute) }
    }

    fun stop() { scope.cancel() }
}