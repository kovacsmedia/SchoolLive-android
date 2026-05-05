package hu.schoollive.player.api

import hu.schoollive.player.api.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("devices/native/provision")
    suspend fun provision(@Body body: ProvisionRequest): Response<ProvisionResponse>

    // A backend `/devices/native/status/:hardwareId` path paramétert vár
    // (NEM header-t és NEM device-key-t). Az aktiválás állapotát a
    // hardwareId-vel kérdezzük le, mert deviceKey csak akkor lesz, ha már
    // aktiválva van. Régen header-rel volt, az 404-et adott → polling
    // sosem detektálta az "active" állapotot.
    @GET("devices/native/status/{hardwareId}")
    suspend fun getStatus(
        @Path("hardwareId") hardwareId: String
    ): Response<ProvisionResponse>

    @GET("devices/native/snap-port")
    suspend fun getSnapPort(
        @Header("x-device-key") deviceKey: String
    ): Response<SnapPortResponse>

    @GET("devices/native/info")
    suspend fun getTenantInfo(
        @Header("x-device-key") deviceKey: String
    ): Response<TenantInfo>

    @GET("bells/sync")
    suspend fun getBells(
        @Header("x-device-key") deviceKey: String
    ): Response<BellsResponse>

    @POST("devices/native/beacon")
    suspend fun beacon(
        @Header("x-device-key") deviceKey: String,
        @Body body: BeaconRequest
    ): Response<BeaconResponse>
}
