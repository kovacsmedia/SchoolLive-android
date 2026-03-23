package hu.schoollive.player.util

import android.content.Context
import android.content.SharedPreferences

object PrefsUtil {
    private const val PREFS_NAME = "schoollive_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Provisioning ──────────────────────────────────────────────
    var serverUrl: String
        get() = _serverUrl
        set(value) { _serverUrl = value }
    private var _serverUrl = ""

    fun getServerUrl(ctx: Context) = prefs(ctx).getString("server_url", "") ?: ""
    fun setServerUrl(ctx: Context, v: String) = prefs(ctx).edit().putString("server_url", v).apply()

    fun getDeviceKey(ctx: Context) = prefs(ctx).getString("device_key", "") ?: ""
    fun setDeviceKey(ctx: Context, v: String) = prefs(ctx).edit().putString("device_key", v).apply()

    fun getHardwareId(ctx: Context) = prefs(ctx).getString("hardware_id", "") ?: ""
    fun setHardwareId(ctx: Context, v: String) = prefs(ctx).edit().putString("hardware_id", v).apply()

    fun getShortId(ctx: Context) = prefs(ctx).getString("short_id", "") ?: ""
    fun setShortId(ctx: Context, v: String) = prefs(ctx).edit().putString("short_id", v).apply()

    fun getDeviceName(ctx: Context) = prefs(ctx).getString("device_name", "Android Player") ?: "Android Player"
    fun setDeviceName(ctx: Context, v: String) = prefs(ctx).edit().putString("device_name", v).apply()

    fun isProvisioned(ctx: Context) = prefs(ctx).getBoolean("provisioned", false)
    fun setProvisioned(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("provisioned", v).apply()

    // ── Snap ──────────────────────────────────────────────────────
    fun getSnapPort(ctx: Context) = prefs(ctx).getInt("snap_port", 0)
    fun setSnapPort(ctx: Context, v: Int) = prefs(ctx).edit().putInt("snap_port", v).apply()

    fun getSnapHost(ctx: Context): String {
        val url = getServerUrl(ctx)
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url
        }
    }

    // ── Bells (JSON string cache) ──────────────────────────────────
    fun getBellsJson(ctx: Context) = prefs(ctx).getString("bells_json", "") ?: ""
    fun setBellsJson(ctx: Context, v: String) = prefs(ctx).edit().putString("bells_json", v).apply()
}
