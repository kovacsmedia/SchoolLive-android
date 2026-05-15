package hu.schoollive.player.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OtaCheckWorker"

/**
 * Periodikus háttér-worker, ami a GitHub Releases-en ellenőrzi az új APK-t.
 *
 * - A `PlayerService.onStartCommand` ütemezi 6 óránként (`enqueueUniquePeriodicWork`,
 *   policy = `KEEP`), tehát szolgáltatás-újraindításkor nem dobja el a már
 *   ütemezett munkát, hanem továbbpörgeti a meglévőt.
 * - A worker újrahasználja a meglévő `OtaManager.checkForUpdate` /
 *   `downloadAndInstall` logikát, így a verzió-összehasonlítás (BuildConfig
 *   vs GitHub `tag_name`) és a `DownloadManager` + ACTION_VIEW telepítés
 *   ugyanaz, mint a service-start-on futó egyszeri check.
 * - A telepítés (`installApk`) a `FOREGROUND` szolgáltatás futásától független,
 *   az Android `DownloadManager` és a rendszer-szintű ACTION_VIEW telepítő
 *   intent felelős a háttér-letöltésért és a telepítő-prompt megjelenítéséért.
 *
 * Megjegyzés: a Linux/Windows updater_client.py 24 órás intervallummal fut,
 * de Android-on (különösen 24/7 kioszk-eszközöknél) érdemes sűrűbben nézni,
 * mert a service ritkán indul újra és így a frissítés sokáig nem jutna el a
 * kliensekhez. 6 óra reális kompromisszum a hálózati terhelés és a frissítési
 * latency között.
 */
class OtaCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val url = OtaManager(ctx).checkForUpdate()
            if (url != null) {
                Log.i(TAG, "Periodikus OTA: új verzió → $url")
                withContext(Dispatchers.Main) {
                    OtaManager(ctx).downloadAndInstall(url)
                }
            } else {
                Log.d(TAG, "Periodikus OTA: nincs új verzió")
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Periodikus OTA hiba: ${e.message}")
            // A WorkManager retry-strategy-jét NEM kérjük: a következő periódusban
            // úgyis újra lefut, és a retry csak feleslegesen pörgetné a hálózatot
            // ideiglenes hiba (pl. Wi-Fi szakadás) esetén.
            Result.success()
        }
    }
}
