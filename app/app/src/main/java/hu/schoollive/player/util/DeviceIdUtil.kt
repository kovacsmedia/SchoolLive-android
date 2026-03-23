package hu.schoollive.player.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object DeviceIdUtil {

    /**
     * Returns a stable, anonymised hardware ID derived from ANDROID_ID via SHA-256.
     * Cached in SharedPreferences on first call.
     */
    fun getHardwareId(ctx: Context): String {
        val cached = PrefsUtil.getHardwareId(ctx)
        if (cached.isNotEmpty()) return cached

        val androidId = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        val hardwareId = "android-$hex"

        PrefsUtil.setHardwareId(ctx, hardwareId)
        return hardwareId
    }

    /**
     * Short display ID: "AL-XXXX" (4 hex chars from hash), shown on screen
     * before activation so admin can identify the device.
     */
    fun getShortId(ctx: Context): String {
        val cached = PrefsUtil.getShortId(ctx)
        if (cached.isNotEmpty()) return cached

        val hwId = getHardwareId(ctx)
        val suffix = hwId.takeLast(4).uppercase()
        val shortId = "AL-$suffix"

        PrefsUtil.setShortId(ctx, shortId)
        return shortId
    }

    /**
     * Generates a random UUID device key (one-time, persisted).
     */
    fun getOrCreateDeviceKey(ctx: Context): String {
        val cached = PrefsUtil.getDeviceKey(ctx)
        if (cached.isNotEmpty()) return cached

        val key = UUID.randomUUID().toString()
        PrefsUtil.setDeviceKey(ctx, key)
        return key
    }
}
