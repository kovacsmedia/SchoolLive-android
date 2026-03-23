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

    fun updateBells(newBells: List<Bell>) {
        bells = newBells
        val json = gson.toJson(newBells)
        PrefsUtil.setBellsJson(ctx, json)
        Log.d(TAG, "Bells updated: ${bells.size}")
    }

    private fun loadFromCache() {
        val json = PrefsUtil.getBellsJson(ctx)
        if (json.isNotEmpty()) {
            try {
                val type = object : TypeToken<List<Bell>>() {}.type
                bells = gson.fromJson(json, type)
                Log.d(TAG, "Bells loaded from cache: ${bells.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Bell cache parse error: ${e.message}")
            }
        }
    }

    /**
     * Returns "HH:MM" of the next upcoming bell today, or null if none.
     */
    fun nextBellTime(): String? {
        val now = Calendar.getInstance()
        val currentDay = (now.get(Calendar.DAY_OF_WEEK) - 2).let { if (it < 0) 6 else it } // Mon=0
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        return bells
            .filter { it.enabled && currentDay in it.days }
            .mapNotNull { bell ->
                val parts = bell.time.split(":")
                if (parts.size != 2) return@mapNotNull null
                val h = parts[0].toIntOrNull() ?: return@mapNotNull null
                val m = parts[1].toIntOrNull() ?: return@mapNotNull null
                val totalMin = h * 60 + m
                if (totalMin > currentMinutes) bell.time else null
            }
            .minOrNull()
    }
}
