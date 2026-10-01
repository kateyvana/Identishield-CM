package cm.identishield.mobile.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Routes du flux A (contracts/mobile-api.yaml), relatives à l'URL de base `…/mobile/v1/`. */
interface MobileApi {
    @POST("enrolment/start")
    suspend fun start(
        @Body body: StartRequest,
    ): Response<StartResponse>

    @POST("enrolment/otp")
    suspend fun otp(
        @Body body: OtpRequest,
    ): Response<BindResponse>

    @POST("enrolment/complete")
    suspend fun complete(
        @Body body: CompleteRequest,
    ): Response<RecoveryCodeIssued>

    @POST("recovery-code/confirm")
    suspend fun confirm(
        @Body body: ConfirmRequest,
    ): Response<SessionResponse>

    @GET("account")
    suspend fun account(
        @Header("Authorization") bearer: String,
    ): Response<Account>
}
