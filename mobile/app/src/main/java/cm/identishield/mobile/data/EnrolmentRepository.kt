package cm.identishield.mobile.data

import cm.identishield.mobile.BuildConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
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
        val retryAfterSeconds: Long? = null, // THROTTLED : en-tête Retry-After (ES-21)
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

    /**
     * Flux D : la clé de récupération saisie, pour CETTE tentative seulement (jamais conservée).
     * Défaut FERMÉ (refus) : une implémentation qui ne la prend pas en charge ne lie jamais rien.
     */
    suspend fun recover(
        enrolmentId: String,
        recoveryCode: String,
    ): ApiResult<BindResponse> = ApiResult.Refused(0, RetrofitEnrolmentRepository.CONTRACT_VIOLATION)

    suspend fun confirm(request: ConfirmRequest): ApiResult<SessionResponse>

    suspend fun account(sessionToken: String): ApiResult<Account>
}

/** Connexions inactives gardées en réserve : l'application parle à un seul serveur. */
private const val MAX_IDLE_CONNECTIONS = 2

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
            // SPEC §7.4 : jamais de renvoi automatique. Par défaut, OkHttp rejoue une requête
            // coupée sur une connexion réutilisée, ce qui rejouerait un envoi d'argent déjà reçu.
            // Amendement d'ADR-037 (M-07).
            .retryOnConnectionFailure(false)
            // ADR-039 (D9) : une connexion inactive est rendue AVANT que le serveur ne la ferme
            // (APP_CONN_KEEPALIVE < SERVER_CONN_KEEPALIVE) ; les lectures seules sont rejouées une fois.
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, BuildConfig.APP_CONN_KEEPALIVE_SECONDS, TimeUnit.SECONDS))
            .addInterceptor(ReadRetryInterceptor())
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

    override suspend fun recover(
        enrolmentId: String,
        recoveryCode: String,
    ) = call { api.recover(RecoverRequest(enrolmentId, recoveryCode)) }

    override suspend fun confirm(request: ConfirmRequest) = call { api.confirm(request) }

    override suspend fun account(sessionToken: String) = call { api.account("Bearer $sessionToken") }

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> = apiCall(block)

    companion object {
        /** Réponse hors contrat reçue par l'application (jamais émise par le serveur). */
        const val CONTRACT_VIOLATION = "CONTRACT_VIOLATION"
    }
}

/** Un appel du contrat : succès, refus avec code de raison (et Retry-After), ou panne réseau. */
internal suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> =
    try {
        val response = block()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            ApiResult.Ok(body)
        } else {
            ApiResult.Refused(response.code(), reasonOf(response), response.headers()["Retry-After"]?.toLongOrNull())
        }
    } catch (e: IOException) {
        ApiResult.Unreachable
    } catch (e: SerializationException) {
        ApiResult.Refused(0, RetrofitEnrolmentRepository.CONTRACT_VIOLATION)
    }

private fun reasonOf(response: Response<*>): String =
    try {
        response.errorBody()?.string()?.let { ContractJson.decodeFromString<ErrorBody>(it).reason }
            ?: RetrofitEnrolmentRepository.CONTRACT_VIOLATION
    } catch (e: SerializationException) {
        RetrofitEnrolmentRepository.CONTRACT_VIOLATION
    } catch (e: IllegalArgumentException) {
        RetrofitEnrolmentRepository.CONTRACT_VIOLATION
    }
