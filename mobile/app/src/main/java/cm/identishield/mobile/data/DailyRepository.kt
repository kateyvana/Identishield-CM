package cm.identishield.mobile.data

/** Flux B (session) et C (envoi signé) côté réseau. L'application transmet et rapporte (P-2). */
interface DailyRepository {
    suspend fun challenge(deviceId: String): ApiResult<ChallengeResponse>

    suspend fun openSession(request: SessionRequest): ApiResult<SessionResponse>

    suspend fun account(sessionToken: String): ApiResult<Account>

    suspend fun transactions(sessionToken: String): ApiResult<TransactionList>

    suspend fun holder(
        sessionToken: String,
        msisdn: String,
    ): ApiResult<Holder>

    suspend fun nonce(sessionToken: String): ApiResult<NonceResponse>

    suspend fun transfer(
        sessionToken: String,
        request: TransferRequest,
    ): ApiResult<TransferResponse>

    suspend fun cancel(
        sessionToken: String,
        transferId: String,
        action: SignedAction,
    ): ApiResult<CancelResponse>
}

class RetrofitDailyRepository(
    private val api: MobileApi,
) : DailyRepository {
    override suspend fun challenge(deviceId: String) = apiCall { api.sessionChallenge(deviceId) }

    override suspend fun openSession(request: SessionRequest) = apiCall { api.openSession(request) }

    override suspend fun account(sessionToken: String) = apiCall { api.account(bearer(sessionToken)) }

    override suspend fun transactions(sessionToken: String) = apiCall { api.transactions(bearer(sessionToken)) }

    override suspend fun holder(
        sessionToken: String,
        msisdn: String,
    ) = apiCall { api.holder(bearer(sessionToken), msisdn) }

    override suspend fun nonce(sessionToken: String) = apiCall { api.nonce(bearer(sessionToken)) }

    override suspend fun transfer(
        sessionToken: String,
        request: TransferRequest,
    ) = apiCall { api.transfer(bearer(sessionToken), request) }

    override suspend fun cancel(
        sessionToken: String,
        transferId: String,
        action: SignedAction,
    ) = apiCall { api.cancel(bearer(sessionToken), transferId, action) }

    private fun bearer(token: String) = "Bearer $token"
}
