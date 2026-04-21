package hu.schoollive.player.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

private const val TAG = "OtaManager"
private const val GITHUB_API =
    "https://api.github.com/repos/[felhasználónév]/SchoolLive-android/releases/latest"
private const val CURRENT_VERSION = "1.0.0"

/**
 * Checks GitHub Releases for a newer APK and installs it.
 *
 * Usage:
 *   lifecycleScope.launch { OtaManager(context).checkAndUpdate() }
 */
class OtaManager(private val ctx: Context) {

    private val http = OkHttpClient()

    /**
     * Fetches latest release tag from GitHub API.
     * Returns the download URL if a newer version exists, null otherwise.
     */
    suspend fun checkForUpdate(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null

            val body = resp.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)

            val latestTag = json.optString("tag_name", "").trimStart('v')
            if (latestTag.isEmpty() || latestTag == CURRENT_VERSION) return@withContext null

            // Compare versions (simple string compare works for semver x.y.z)
            if (!isNewerVersion(latestTag, CURRENT_VERSION)) return@withContext null

            // Find APK asset URL
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    Log.i(TAG, "Update found: $latestTag → ${asset.optString("browser_download_url")}")
                    return@withContext asset.optString("browser_download_url")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA check failed: ${e.message}")
        }
        null
    }

    /**
     * Downloads APK via DownloadManager and triggers install intent.
     */
    fun downloadAndInstall(apkUrl: String) {
        // DownloadManager cannot write to internal cacheDir, use externalCacheDir instead
        val apkDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "apk")
        apkDir.mkdirs()
        val apkFile = File(apkDir, "schoollive-update.apk")
        if (apkFile.exists()) apkFile.delete()

        val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("SchoolLive frissítés")
            setDescription("Letöltés…")
            setDestinationUri(Uri.fromFile(apkFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(req)

        // Listen for completion
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(apkFile)
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        ctx.startActivity(intent)
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
