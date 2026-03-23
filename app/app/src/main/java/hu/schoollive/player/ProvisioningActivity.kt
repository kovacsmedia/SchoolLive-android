package hu.schoollive.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import hu.schoollive.player.api.ApiClient
import hu.schoollive.player.api.models.ProvisionRequest
import hu.schoollive.player.databinding.ActivityProvisioningBinding
import hu.schoollive.player.util.DeviceIdUtil
import hu.schoollive.player.util.PrefsUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt

class ProvisioningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisioningBinding
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fullscreen dark
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        binding = ActivityProvisioningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val shortId = DeviceIdUtil.getShortId(this)
        binding.tvShortId.text = shortId
        binding.tvStatus.text = "Adja meg a szerver adatait"

        binding.btnConnect.setOnClickListener { startProvisioning() }
    }

    private fun startProvisioning() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Szerver URL kötelező!", Toast.LENGTH_SHORT).show()
            return
        }

        PrefsUtil.setServerUrl(this, serverUrl)

        val hardwareId = DeviceIdUtil.getHardwareId(this)
        val deviceKey  = DeviceIdUtil.getOrCreateDeviceKey(this)
        val shortId    = DeviceIdUtil.getShortId(this)

        // Hash deviceKey with bcrypt (cost 10)
        val deviceKeyHash = BCrypt.hashpw(deviceKey, BCrypt.gensalt(10))

        binding.progressBar.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "Regisztrálás…"

        lifecycleScope.launch {
            try {
                val api = ApiClient.get(serverUrl)
                val resp = api.provision(
                    ProvisionRequest(
                        hardwareId    = hardwareId,
                        deviceKeyHash = deviceKeyHash,
                        shortId       = shortId
                    )
                )

                if (!resp.isSuccessful) {
                    showError("Hiba: ${resp.code()} ${resp.message()}")
                    return@launch
                }

                val body = resp.body()!!
                if (body.status == "active") {
                    activationSuccess()
                } else {
                    // pending – start polling
                    binding.tvStatus.text =
                        "Várakozás aktiválásra…\nAzonosító: $shortId\n(Aktiválja az admin felületen)"
                    polling = true
                    pollActivation(serverUrl, deviceKey)
                }
            } catch (e: Exception) {
                showError("Kapcsolati hiba: ${e.message}")
            }
        }
    }

    private suspend fun pollActivation(serverUrl: String, deviceKey: String) {
        val api = ApiClient.get(serverUrl)
        while (polling) {
            delay(3_000)
            try {
                val resp = api.getStatus(deviceKey)
                if (resp.isSuccessful && resp.body()?.status == "active") {
                    activationSuccess()
                    return
                }
            } catch (e: Exception) {
                // ignore, keep polling
            }
        }
    }

    private suspend fun activationSuccess() {
        // Fetch snap port
        val serverUrl = PrefsUtil.getServerUrl(this)
        val deviceKey = PrefsUtil.getDeviceKey(this)
        try {
            val snapResp = ApiClient.get(serverUrl).getSnapPort(deviceKey)
            if (snapResp.isSuccessful) {
                snapResp.body()?.snapPort?.let { PrefsUtil.setSnapPort(this, it) }
            }
        } catch (e: Exception) { /* ok, will retry in service */ }

        PrefsUtil.setProvisioned(this, true)
        polling = false

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.btnConnect.isEnabled = true
        binding.tvStatus.text = msg
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
    }
}
