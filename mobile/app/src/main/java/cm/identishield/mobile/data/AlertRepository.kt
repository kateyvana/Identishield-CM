package cm.identishield.mobile.data

/** Flux F côté réseau (M-08) : alertes du compte et réponses signées « c'est moi / ce n'est pas moi ». */
interface AlertRepository {
    suspend fun alerts(sessionToken: String): ApiResult<AlertList>

    suspend fun nonce(sessionToken: String): ApiResult<NonceResponse>

    suspend fun acknowledge(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ): ApiResult<AlertActionResponse>

    suspend fun deny(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ): ApiResult<AlertActionResponse>
}

class RetrofitAlertRepository(
    private val api: MobileApi,
) : AlertRepository {
    override suspend fun alerts(sessionToken: String) = apiCall { api.alerts(bearer(sessionToken)) }

    override suspend fun nonce(sessionToken: String) = apiCall { api.nonce(bearer(sessionToken)) }

    override suspend fun acknowledge(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ) = apiCall { api.acknowledgeAlert(bearer(sessionToken), alertId, action) }

    override suspend fun deny(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ) = apiCall { api.denyAlert(bearer(sessionToken), alertId, action) }

    private fun bearer(token: String) = "Bearer $token"
}
