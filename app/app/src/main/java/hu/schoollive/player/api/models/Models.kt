package hu.schoollive.player.api.models

import com.google.gson.annotations.SerializedName

// ── Provisioning ──────────────────────────────────────────────────────────────

data class ProvisionRequest(
    val hardwareId: String,
    val deviceKeyHash: String,   // bcrypt hash of the device key
    val shortId: String,
    val platform: String = "android",
    val appVersion: String = "1.0.0"
)

data class ProvisionResponse(
    val status: String,          // "pending" | "active"
    val deviceId: String?,
    val tenantId: String?,
    val tenantName: String?
)

// ── Snap port ────────────────────────────────────────────────────────────────

data class SnapPortResponse(
    val snapPort: Int,
    val snapHost: String?
)

// ── Tenant info ───────────────────────────────────────────────────────────────

data class TenantInfo(
    val id: String,
    val name: String,
    val logoUrl: String?
)

// ── Bells ─────────────────────────────────────────────────────────────────────

data class Bell(
    val id: String,
    val name: String,
    val time: String,            // "HH:MM"
    val days: List<Int>,         // 0=Mon … 6=Sun
    val enabled: Boolean,
    val audioUrl: String?
)

data class BellsResponse(
    val bells: List<Bell>
)

// ── Beacon / poll ─────────────────────────────────────────────────────────────

data class BeaconRequest(
    val deviceKey: String,
    val snapConnected: Boolean,
    val wsConnected: Boolean,
    val volume: Int
)

data class BeaconResponse(
    val ok: Boolean
)
