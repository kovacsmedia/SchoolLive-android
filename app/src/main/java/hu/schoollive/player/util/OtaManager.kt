package hu.schoollive.player.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import hu.schoollive.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val TAG = "OtaManager"
private const val GITHUB_API =
    "https://api.github.com/repos/kovacsmedia/SchoolLive-android/releases/latest"

class OtaManager(private val ctx: Context) {

    private val http = OkHttpClient()

    // BuildConfig.VERSION_NAME – automatikusan frissül minden buildnél
    private val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun checkForUpdate(): String? = withContext(Dispatchers.IO) {
        try {
            val resp = http.newCall(
                Request.Builder()
                    .url(GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
            ).execute()

            if (!resp.isSuccessful) return@withContext null
            val json = org.json.JSONObject(resp.body?.string() ?: return@withContext null)

            val latestTag = json.optString("tag_name", "").trimStart('v')
            if (latestTag.isEmpty()) return@withContext null

            Log.d(TAG, "OTA: current=$currentVersion latest=$latestTag")
            if (!isNewerVersion(latestTag, currentVersion)) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name", "").endsWith(".apk")) {
                    val url = asset.optString("browser_download_url")
                    Log.i(TAG, "Frissítés: $latestTag → $url")
                    return@withContext url
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA check hiba: ${e.message}")
        }
        null
    }

    fun downloadAndInstall(apkUrl: String) {
        val apkFile = File(ctx.cacheDir.also { File(it, "apk").mkdirs() }
            .let { File(it, "apk") }, "schoollive-update.apk")

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(
            DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("SchoolLive frissítés")
                setDescription("Letöltés…")
                setDestinationUri(Uri.fromFile(apkFile))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            }
        )

        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
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
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
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