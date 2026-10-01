package cm.identishield.mobile.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Routes des flux A, B et C (contracts/mobile-api.yaml), relatives à l'URL de base `…/mobile/v1/`. */
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

    @GET("session/challenge")
    suspend fun sessionChallenge(
        @Query("deviceId") deviceId: String,
    ): Response<ChallengeResponse>

    @POST("session")
    suspend fun openSession(
        @Body body: SessionRequest,
    ): Response<SessionResponse>

    @POST("nonce")
    suspend fun nonce(
        @Header("Authorization") bearer: String,
    ): Response<NonceResponse>

    @GET("transactions")
    suspend fun transactions(
        @Header("Authorization") bearer: String,
    ): Response<TransactionList>

    @GET("beneficiaries/{msisdn}/holder")
    suspend fun holder(
        @Header("Authorization") bearer: String,
        @Path("msisdn") msisdn: String,
    ): Response<Holder>

    @POST("transfers")
    suspend fun transfer(
        @Header("Authorization") bearer: String,
        @Body body: TransferRequest,
    ): Response<TransferResponse>

    @POST("transfers/{transferId}/cancel")
    suspend fun cancel(
        @Header("Authorization") bearer: String,
        @Path("transferId") transferId: String,
        @Body body: SignedAction,
    ): Response<CancelResponse>
}
