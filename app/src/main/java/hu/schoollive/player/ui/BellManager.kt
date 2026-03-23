package hu.schoollive.player.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.schoollive.player.api.models.Bell
import hu.schoollive.player.util.PrefsUtil
import java.util.Calendar

private const val TAG = "BellManager"

class BellManager(private val ctx: Context) {

    private var bells: List<Bell> = emptyList()
    private val gson = Gson()

    init { loadFromCache() }

    // A backend mindig az aktuális napra szűrt listát adja vissza,
    // ezért egyszerűen az egész listát tároljuk
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
            bells = gson.fromJson(json, type)
            Log.d(TAG, "Bells loaded from cache: ${bells.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Bell cache parse error: ${e.message}")
        }
    }

    /**
     * Returns "HH:MM" of the next upcoming bell today, or null if none.
     * A backend /bells/sync már a mai napra feloldott listát adja –
     * napszűrés nem szükséges, csak az idő alapján keresünk.
     */
    fun nextBellTime(): String? {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        return bells
            .filter { bell -> bell.hour * 60 + bell.minute > currentMinutes }
            .minByOrNull { bell -> bell.hour * 60 + bell.minute }
            ?.let { bell -> "%02d:%02d".format(bell.hour, bell.minute) }
    }
}