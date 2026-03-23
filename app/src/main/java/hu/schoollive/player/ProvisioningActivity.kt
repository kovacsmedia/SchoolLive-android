package hu.schoollive.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import hu.schoollive.player.api.ApiClient
import hu.schoollive.player.api.models.ProvisionRequest
import hu.schoollive.player.databinding.ActivityProvisioningBinding
import hu.schoollive.player.util.DeviceIdUtil
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt

class ProvisioningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisioningBinding
    private var polling = false

    companion object {
        const val SERVER_URL = "https://api.schoollive.hu"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        binding = ActivityProvisioningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PrefsUtil.setServerUrl(this, SERVER_URL)
        binding.tvShortId.text = DeviceIdUtil.getShortId(this)
        binding.tvStatus.text  = "Csatlakozás a SchoolLive szerverrel…"

        binding.btnConnect.setOnClickListener {
            binding.btnConnect.visibility  = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            startProvisioning()
        }

        startProvisioning()
    }

    private fun startProvisioning() {
        val activity      = this          // Activity referencia rögzítése
        val hardwareId    = DeviceIdUtil.getHardwareId(activity)
        val deviceKey     = DeviceIdUtil.getOrCreateDeviceKey(activity)
        val shortId       = DeviceIdUtil.getShortId(activity)
        val deviceKeyHash = BCrypt.hashpw(deviceKey, BCrypt.gensalt(10))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = ApiClient.get(SERVER_URL).provision(
                    ProvisionRequest(
                        hardwareId    = hardwareId,
                        deviceKeyHash = deviceKeyHash,
                        shortId       = shortId
                    )
                )

                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        showError("Szerver hiba: ${resp.code()}")
                    }
                    return@launch
                }

                when (resp.body()?.status) {
                    "active" -> activationSuccess(activity, deviceKey)
                    else -> {
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnConnect.visibility  = View.GONE
                            binding.tvStatus.text =
                                "Várakozás aktiválásra…\n\n" +
                                        "Azonosító: $shortId\n\n" +
                                        "Aktiválja az admin felületen!"
                        }
                        polling = true
                        pollActivation(activity, hardwareId, deviceKey)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Kapcsolati hiba:\n${e.message}")
                }
            }
        }
    }

    private suspend fun pollActivation(
        activity: ProvisioningActivity,
        hardwareId: String,
        deviceKey: String
    ) {
        val api = ApiClient.get(SERVER_URL)
        while (polling) {
            delay(3_000)
            try {
                val resp = api.getStatus(hardwareId)
                if (resp.isSuccessful && resp.body()?.status == "active") {
                    activationSuccess(activity, deviceKey)
                    return
                }
            } catch (e: Exception) {
                // hálózati hiba – tovább pollozunk
            }
        }
    }

    private suspend fun activationSuccess(
        activity: ProvisioningActivity,
        deviceKey: String
    ) {
        // Snap port lekérése IO szálon
        try {
            val snapResp = ApiClient.get(SERVER_URL).getSnapPort(deviceKey)
            if (snapResp.isSuccessful) {
                snapResp.body()?.snapPort?.let {
                    PrefsUtil.setSnapPort(activity, it)
                }
            }
        } catch (e: Exception) { /* service újrapróbálja */ }

        PrefsUtil.setProvisioned(activity, true)
        polling = false

        // UI műveletek főszálon
        withContext(Dispatchers.Main) {
            activity.startActivity(Intent(activity, MainActivity::class.java))
            activity.finish()
        }
    }

    private fun showError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnConnect.visibility  = View.VISIBLE
        binding.tvStatus.text          = msg
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
    }
}