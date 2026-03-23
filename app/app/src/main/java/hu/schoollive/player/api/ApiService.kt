package hu.schoollive.player.api

import hu.schoollive.player.api.models.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("devices/android/provision")
    suspend fun provision(@Body body: ProvisionRequest): Response<ProvisionResponse>

    @GET("devices/android/status")
    suspend fun getStatus(
        @Header("x-device-key") deviceKey: String
    ): Response<ProvisionResponse>

    @GET("devices/android/snap-port")
    suspend fun getSnapPort(
        @Header("x-device-key") deviceKey: String
    ): Response<SnapPortResponse>

    @GET("tenant/info")
    suspend fun getTenantInfo(
        @Header("x-device-key") deviceKey: String
    ): Response<TenantInfo>

    @GET("bells/sync")
    suspend fun getBells(
        @Header("x-device-key") deviceKey: String
    ): Response<BellsResponse>

    @POST("devices/beacon")
    suspend fun beacon(
        @Header("x-device-key") deviceKey: String,
        @Body body: BeaconRequest
    ): Response<BeaconResponse>
}
