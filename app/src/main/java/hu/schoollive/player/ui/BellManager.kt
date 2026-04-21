package hu.schoollive.player.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.schoollive.player.api.models.Bell
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.*
import java.util.Calendar

private const val TAG              = "BellManager"
private const val TICK_INTERVAL_MS = 5_000L
private const val PLAY_WINDOW_SEC  = 30     // :00-:30 másodperces ablakon belül szólhat

class BellManager(private val ctx: Context) {

    private var bells: List<Bell> = emptyList()
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Deduplication ─────────────────────────────────────────────────────────
    // "YYYY-MM-DD:HH:MM" – ha szerver és offline tick ugyanarra a percre triggerel,
    // csak egyszer szól. Szerver BELL → registerServerBell(), offline tick
    // → ugyanezt a kulcsot ellenőrzi.
    @Volatile private var lastPlayedKey = ""

    // ── Offline mód feltétel ──────────────────────────────────────────────────
    // Offline tick CSAK ha snap ÉS ws egyszerre offline.
    @Volatile private var snapOnline = false
    @Volatile private var wsOnline   = false

    val isOfflineMode: Boolean get() = !snapOnline && !wsOnline

    // Callback: offline bell lejátszandó
    var onOfflineBell: ((soundFile: String) -> Unit)? = null

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

    // ── Szerver bell regisztrálása dedup-hoz ──────────────────────────────────
    // PlayerService hívja minden BELL eseménynél (online és broadcastolt is),
    // hogy az offline tick ne szóljon ugyanarra a percre.

    fun registerBell() {
        val key = bellKey()
        if (lastPlayedKey != key) {
            lastPlayedKey = key
            Log.d(TAG, "Bell regisztrálva (dedup): $key")
        }
    }

    // ── Offline tick ──────────────────────────────────────────────────────────

    private fun startOfflineTick() {
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                checkOfflineBell()
            }
        }
    }

    private fun checkOfflineBell() {
        // Feltételek:
        //  1. Mindkét kapcsolat offline
        //  2. Van bell lista
        //  3. A másodperc a lejátszási ablakon belül van (:00-:30)
        //  4. Az adott perc még nem szólt (dedup)
        //  5. Az adott percre van bejegyzett csengetés
        if (!isOfflineMode)   return
        if (bells.isEmpty())  return

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND)
        if (sec > PLAY_WINDOW_SEC) return

        val key = bellKey()
        if (lastPlayedKey == key) return

        val hour   = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val due    = bells.find { it.hour == hour && it.minute == minute } ?: return

        lastPlayedKey = key
        Log.d(TAG, "Offline bell: ${due.soundFile} @ $key (sec=$sec)")
        onOfflineBell?.invoke(due.soundFile)
    }

    // "YYYY-MM-DD:HH:MM"
    private fun bellKey(): String {
        val now = Calendar.getInstance()
        return "%04d-%02d-%02d:%02d:%02d".format(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
        )
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