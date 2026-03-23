package hu.schoollive.player.api.models

import com.google.gson.annotations.SerializedName

// ── Provisioning ──────────────────────────────────────────────────────────────

data class ProvisionRequest(
    val hardwareId: String,
    val deviceKeyHash: String,
    val shortId: String,
    val platform: String = "android",
    val appVersion: String = "1.0.0"
)

data class ProvisionResponse(
    val status: String,          // "pending" | "active"
    val deviceId: String?
)

// ── Snap port ─────────────────────────────────────────────────────────────────

data class SnapPortResponse(
    val ok: Boolean,
    val snapPort: Int
)

// ── Tenant info ───────────────────────────────────────────────────────────────

data class TenantInfo(
    val ok: Boolean,
    val tenantName: String?,
    val deviceId: String?
)

// ── Bells – pontosan amit a /bells/sync visszaad ──────────────────────────────

data class Bell(
    val hour: Int,
    val minute: Int,
    val type: String,        // "MAIN" | "SIGNAL"
    val soundFile: String
)

data class BellsResponse(
    val ok: Boolean,
    val isHoliday: Boolean,
    val bells: List<Bell>,
    val defaultBells: List<Bell>
)

// ── Beacon ────────────────────────────────────────────────────────────────────

data class BeaconRequest(
    val deviceKey: String,
    val snapConnected: Boolean,
    val wsConnected: Boolean,
    val volume: Int,
    val platform: String = "android",
    val appVersion: String = "unknown"
)

data class BeaconResponse(
    val ok: Boolean,
    val deviceId: String?
)