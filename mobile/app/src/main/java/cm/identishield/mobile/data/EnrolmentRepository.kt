package cm.identishield.mobile.data

import cm.identishield.mobile.BuildConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Résultat d'un appel : succès, refus du serveur (code de raison du contrat), ou panne réseau. */
sealed interface ApiResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ApiResult<T>

    data class Refused(
        val status: Int,
        val reason: String,
    ) : ApiResult<Nothing>

    data object Unreachable : ApiResult<Nothing>
}

/** Flux A côté réseau. L'application ne décide rien (P-2) : elle transmet et rapporte. */
interface EnrolmentRepository {
    suspend fun start(
        msisdn: String,
        pin: String,
    ): ApiResult<StartResponse>

    suspend fun verifyOtp(
        enrolmentId: String,
        otp: String,
    ): ApiResult<BindResponse>

    suspend fun complete(request: CompleteRequest): ApiResult<RecoveryCodeIssued>

    suspend fun confirm(request: ConfirmRequest): ApiResult<SessionResponse>

    suspend fun account(sessionToken: String): ApiResult<Account>
}

/** JSON strict : un champ hors contrat est refusé (additionalProperties: false du contrat). */
val ContractJson =
    Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

fun buildMobileApi(
    baseUrl: String,
    client: OkHttpClient =
        OkHttpClient
            .Builder()
            // MOB-NF-03 : APP_NETWORK_TIMEOUT du jeu de règles, lu à la construction.
            .connectTimeout(BuildConfig.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(BuildConfig.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(BuildConfig.NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
): MobileApi =
    Retrofit
        .Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(ContractJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MobileApi::class.java)

class RetrofitEnrolmentRepository(
    private val api: MobileApi,
) : EnrolmentRepository {
    override suspend fun start(
        msisdn: String,
        pin: String,
    ) = call { api.start(StartRequest(msisdn = msisdn, pin = pin)) }

    override suspend fun verifyOtp(
        enrolmentId: String,
        otp: String,
    ) = call { api.otp(OtpRequest(enrolmentId, otp)) }

    override suspend fun complete(request: CompleteRequest) = call { api.complete(request) }

    override suspend fun confirm(request: ConfirmRequest) = call { api.confirm(request) }

    override suspend fun account(sessionToken: String) = call { api.account("Bearer $sessionToken") }

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> =
        try {
            val response = block()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                ApiResult.Ok(body)
            } else {
                ApiResult.Refused(response.code(), reasonOf(response))
            }
        } catch (e: IOException) {
            ApiResult.Unreachable
        } catch (e: SerializationException) {
            ApiResult.Refused(0, CONTRACT_VIOLATION)
        }

    private fun reasonOf(response: Response<*>): String =
        try {
            response.errorBody()?.string()?.let { ContractJson.decodeFromString<ErrorBody>(it).reason }
                ?: CONTRACT_VIOLATION
        } catch (e: SerializationException) {
            CONTRACT_VIOLATION
        } catch (e: IllegalArgumentException) {
            CONTRACT_VIOLATION
        }

    companion object {
        /** Réponse hors contrat reçue par l'application (jamais émise par le serveur). */
        const val CONTRACT_VIOLATION = "CONTRACT_VIOLATION"
    }
}
