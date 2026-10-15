package cm.identishield.mobile.daily

import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.CancelResponse
import cm.identishield.mobile.data.ChallengeResponse
import cm.identishield.mobile.data.DailyRepository
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.data.NonceResponse
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SessionRequest
import cm.identishield.mobile.data.SessionResponse
import cm.identishield.mobile.data.SignedAction
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.data.TransactionList
import cm.identishield.mobile.data.TransferRequest
import cm.identishield.mobile.data.TransferResponse
import cm.identishield.mobile.security.BoundDevice
import cm.identishield.mobile.security.BoundDeviceStore
import cm.identishield.mobile.security.DeviceKeyInvalidatedException
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.LocalUnlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

private const val ME = "677000123"
private const val DEVICE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
private const val PIN = "1234"
private const val CHALLENGE = "défi_é-AAEC" // non ASCII et « base64 » d'apparence : signé tel quel
private const val NONCE_1 = "0123456789abcdef0123456789abcdef"
private const val NONCE_2 = "fedcba9876543210fedcba9876543210"
private const val SESSION_FROM_E09 = "jeton-e09" // jeton fictif ouvert en fin de flux A (E-09)
private val ACCOUNT = Account("acc-000123", ME, "Amina NDONGO ÉLOMO", 152300, "TRUSTED", emptyList())
private val JEAN = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = true)
private val TXS =
    listOf(
        Transaction("tr-3", "OUT", 8000, "677000789", "Marie ABENA", "EXECUTED", "2026-09-24T09:12:00Z"),
        Transaction("tr-2", "IN", 100000, "677000999", null, "EXECUTED", "2026-09-23T10:00:00Z"),
        Transaction("tr-1", "OUT", 5000, "677000789", "Marie ABENA", "EXECUTED", "2026-09-22T10:00:00Z"),
        Transaction("tr-0", "OUT", 1000, "677000111", "Bloqué", "FROZEN", "2026-09-20T10:00:00Z"),
    )

/** Doublure du serveur : réponses programmées, appels enregistrés. */
private class FakeDaily : DailyRepository {
    val calls = mutableListOf<String>()
    var challengeResult: ApiResult<ChallengeResponse> = ApiResult.Ok(ChallengeResponse(CHALLENGE))
    var sessionResult: ApiResult<SessionResponse> = ApiResult.Ok(SessionResponse("jeton", 300))
    var accountResult: ApiResult<Account> = ApiResult.Ok(ACCOUNT)
    var transactionsResult: ApiResult<TransactionList> = ApiResult.Ok(TransactionList(TXS))
    var holderResult: ApiResult<Holder> = ApiResult.Ok(JEAN)
    val nonces = ArrayDeque(listOf(NONCE_1, NONCE_2))
    var nonceResult: ApiResult<NonceResponse>? = null
    var transferResult: ApiResult<TransferResponse> =
        ApiResult.Ok(TransferResponse("tr-42", "ALLOW", "EXECUTED", balanceXaf = 127300))
    var transferGate: CompletableDeferred<Unit>? = null
    var cancelResult: ApiResult<CancelResponse> = ApiResult.Ok(CancelResponse("tr-42", "CANCELLED"))
    var lastSession: SessionRequest? = null
    val transfers = mutableListOf<TransferRequest>()
    var lastCancel: SignedAction? = null
    var lastHolderQuery: String? = null

    override suspend fun challenge(deviceId: String) = challengeResult.also { calls += "challenge:$deviceId" }

    override suspend fun openSession(request: SessionRequest) =
        sessionResult.also {
            calls += "session"
            lastSession = request
        }

    override suspend fun account(sessionToken: String) = accountResult.also { calls += "account" }

    override suspend fun transactions(sessionToken: String) = transactionsResult.also { calls += "transactions" }

    override suspend fun holder(
        sessionToken: String,
        msisdn: String,
    ) = holderResult.also {
        calls += "holder"
        lastHolderQuery = msisdn
    }

    override suspend fun nonce(sessionToken: String): ApiResult<NonceResponse> {
        calls += "nonce"
        return nonceResult ?: ApiResult.Ok(NonceResponse(nonces.removeFirst()))
    }

    override suspend fun transfer(
        sessionToken: String,
        request: TransferRequest,
    ): ApiResult<TransferResponse> {
        calls += "transfer"
        transfers += request
        transferGate?.await()
        return transferResult
    }

    override suspend fun cancel(
        sessionToken: String,
        transferId: String,
        action: SignedAction,
    ) = cancelResult.also {
        calls += "cancel:$transferId"
        lastCancel = action
    }
}

/** Doublure de la puce : une vraie paire EC logicielle, pour VÉRIFIER chaque signature. */
private class FakeKeys : DeviceKeyManager {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    var present = true
    var invalidated = false

    fun verifies(
        payload: ByteArray,
        base64: String,
    ) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public)
        update(payload)
        verify(Base64.getDecoder().decode(base64))
    }

    override fun hasDeviceKey() = present

    override fun generate(attestationChallenge: ByteArray): GeneratedKey = error("aucune clé n'est créée dans les flux B et C")

    override fun newSigner(): Signature {
        if (invalidated) throw DeviceKeyInvalidatedException(IllegalStateException("nouvelle biométrie"))
        return Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
    }

    override fun delete() = error("les flux B et C ne détruisent jamais la clé")
}

private class MemoryStore(
    var device: BoundDevice? = BoundDevice(ME, DEVICE),
) : BoundDeviceStore {
    override fun load() = device

    override fun save(device: BoundDevice) {
        this.device = device
    }
}

class DailyViewModelTest {
    private val repo = FakeDaily()
    private val keys = FakeKeys()
    private val store = MemoryStore()
    private val session = SessionHolder()
    private val unlockOk = LocalUnlock { s, p -> s.update(p).let { s.sign() } }
    private val unlockCancelled = LocalUnlock { _, _ -> null }

    private fun vm() = DailyViewModel(repo, keys, store, session, CoroutineScope(Dispatchers.Unconfined))

    private fun opened(): DailyViewModel =
        vm().apply {
            start()
            unlock(PIN, unlockOk)
        }

    private fun untilAuthorize(vm: DailyViewModel) {
        vm.startSend()
        vm.chooseBeneficiary("677000456")
        vm.submitAmount(25000)
        vm.confirmTransfer()
    }

    // ---- flux B --------------------------------------------------------------------------------

    @Test
    fun start_withoutLocalBinding_isNotBound() {
        store.device = null
        assertEquals(DailyStep.NotBound, vm().apply { start() }.step.value)
        store.device = BoundDevice(ME, DEVICE)
        keys.present = false
        assertEquals("device_id sans clé : pas lié", DailyStep.NotBound, vm().apply { start() }.step.value)
    }

    @Test
    fun start_showsTheRememberedNumber_neverAName() {
        assertEquals(DailyStep.Unlock(ME), vm().apply { start() }.step.value)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun unlock_signsTheUtf8BytesOfTheChallengeAsReceived_thenOpensHome() {
        val vm = opened()
        val sent = repo.lastSession!!
        assertEquals(SessionRequest(ME, PIN, DEVICE, CHALLENGE, sent.signature), sent)
        assertTrue("signature des octets UTF-8 du défi, sans décodage", keys.verifies(CHALLENGE.toByteArray(Charsets.UTF_8), sent.signature))
        assertEquals(listOf("challenge:$DEVICE", "session", "account", "transactions"), repo.calls)
        assertEquals(DailyStep.Home(ACCOUNT, TXS), vm.step.value)
        assertEquals("jeton", session.token)
    }

    @Test
    fun es22_unknownAccountAndWrongPin_sameMessage() {
        repo.sessionResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        val vm = opened()
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.CREDENTIALS_INVALID)), vm.step.value)
        assertNull(session.token)
    }

    @Test
    fun throttled_showsRetryAfter_neverABlockedAccount() {
        repo.sessionResult = ApiResult.Refused(429, "THROTTLED", 90)
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.THROTTLED, 90)), opened().step.value)
    }

    @Test
    fun keyRevokedOrFrozenAccount_areSimpleMessages() {
        repo.sessionResult = ApiResult.Refused(403, "KEY_REVOKED")
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.KEY_REVOKED)), opened().step.value)
        repo.sessionResult = ApiResult.Refused(403, "ACCOUNT_FROZEN")
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.ACCOUNT_FROZEN)), opened().step.value)
    }

    @Test
    fun cancelledUnlock_sendsNothing() {
        val vm = vm().apply { start() }
        vm.unlock(PIN, unlockCancelled)
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.UNLOCK_CANCELLED)), vm.step.value)
        assertFalse(repo.calls.contains("session"))
    }

    @Test
    fun keyInvalidatedByNewBiometrics_isASimpleMessage_andSendsNothing() {
        keys.invalidated = true
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.KEY_INVALIDATED)), opened().step.value)
        assertFalse(repo.calls.contains("session"))
    }

    @Test
    fun startAgain_afterARotation_neverResetsAFlowInProgress() {
        val vm = opened()
        untilAuthorize(vm)
        val before = repo.calls.size
        vm.start() // l'activité recréée relance start()
        assertEquals(DailyStep.Authorize(JEAN, 25000), vm.step.value)
        assertEquals("aucun appel", before, repo.calls.size)
    }

    @Test
    fun sessionOpenedByEnrolment_goesStraightHome_withoutAChallenge() {
        session.token = SESSION_FROM_E09
        val vm = vm().apply { start() }
        assertEquals(DailyStep.Home(ACCOUNT, TXS), vm.step.value)
        assertEquals(listOf("account", "transactions"), repo.calls)
    }

    @Test
    fun expiredSessionOnARead_returnsToUnlock_andForgetsTheToken() {
        val vm = opened()
        repo.transactionsResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        vm.openHistory()
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.SESSION_EXPIRED)), vm.step.value)
        assertNull(session.token)
    }

    @Test
    fun history_showsTheContractFields() {
        val vm = opened()
        vm.openHistory()
        assertEquals(DailyStep.History(TXS), vm.step.value)
        vm.back()
        assertTrue(vm.step.value is DailyStep.Home)
    }

    // ---- flux C --------------------------------------------------------------------------------

    @Test
    fun recents_areExecutedOutgoingPayees_withoutDuplicates() {
        assertEquals(listOf(TXS[0]), DailyViewModel.recentBeneficiaries(TXS))
    }

    @Test
    fun beneficiary_formIsCheckedLocally_existenceByTheServer() {
        val vm = opened()
        vm.startSend()
        assertEquals(DailyStep.Beneficiary(listOf(TXS[0])), vm.step.value)
        vm.chooseBeneficiary("67700045")
        assertEquals(Problem(DailyError.NUMBER_INVALID), (vm.step.value as DailyStep.Beneficiary).problem)
        assertFalse(repo.calls.contains("holder"))
        repo.holderResult = ApiResult.Refused(404, "REQUEST_INVALID")
        vm.chooseBeneficiary("677 000 456")
        assertEquals("677000456", repo.lastHolderQuery)
        assertEquals(Problem(DailyError.BENEFICIARY_UNKNOWN), (vm.step.value as DailyStep.Beneficiary).problem)
        repo.holderResult = ApiResult.Refused(429, "THROTTLED", 30)
        vm.chooseBeneficiary("677000456")
        assertEquals(Problem(DailyError.THROTTLED, 30), (vm.step.value as DailyStep.Beneficiary).problem)
    }

    @Test
    fun allow_signsTheAdr018Chain_withTheServerNonce_andShowsTheReceipt() {
        val vm = opened()
        vm.startSend()
        vm.chooseBeneficiary("677000456")
        assertEquals(DailyStep.Amount(JEAN, 152300), vm.step.value)
        vm.submitAmount(25000)
        assertEquals(DailyStep.Confirm(JEAN, 25000), vm.step.value)
        vm.confirmTransfer()
        assertEquals(DailyStep.Authorize(JEAN, 25000), vm.step.value)
        vm.authorize(PIN, unlockOk)
        val sent = repo.transfers.single()
        assertEquals(TransferRequest("P2P", 25000, "acc-000456", NONCE_1, sent.signature, PIN), sent)
        val chain = "acc-000123|P2P|25000|acc-000456|$NONCE_1".toByteArray(Charsets.UTF_8)
        assertTrue("signature de la chaîne ADR-018", keys.verifies(chain, sent.signature))
        assertEquals(DailyStep.Sent(JEAN, 25000, "tr-42"), vm.step.value)
    }

    /**
     * Rejeu (décision du propriétaire du 2026-10-10, carte B2) : le PDP refuse un nonce déjà consommé
     * (409 NONCE_CONSUMED, contrat evaluate) ; le cœur en fait la décision REJECTED du contrat mobile.
     * L'appli l'affiche comme « Opération rejetée » (E-18), jamais comme une erreur générique, et ne
     * renvoie rien.
     */
    // @covers ES-10
    @Test
    fun replayedNonce_isPresentedAsRejected_onE18_andNeverResent() {
        repo.transferResult = ApiResult.Ok(TransferResponse("tr-42", "REJECTED", "REJECTED", "DECISION_REJECTED", null, 152300))
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertEquals(DailyStep.Stopped(Outcome.REJECTED), vm.step.value)
        assertEquals("un seul envoi : jamais de renvoi", 1, repo.transfers.size)
    }

    @Test
    fun theFourDecisions_eachHaveTheirScreen() {
        val decisions =
            mapOf(
                TransferResponse("tr-42", "STEP_UP", "DEFERRED", "DECISION_DEFERRED", "2026-10-01T16:00:00Z", 152300) to
                    DailyStep.Deferred("tr-42", 25000, "677000456", "Jean-Paul NKOA", "2026-10-01T16:00:00Z"),
                TransferResponse("tr-42", "FREEZE", "FROZEN", "DECISION_FROZEN", null, 152300) to DailyStep.Stopped(Outcome.FROZEN),
                TransferResponse("tr-42", "REJECTED", "REJECTED", "DECISION_REJECTED", null, 152300) to DailyStep.Stopped(Outcome.REJECTED),
            )
        decisions.forEach { (response, expected) ->
            repo.nonces.addLast(NONCE_1)
            repo.transferResult = ApiResult.Ok(response)
            val vm = opened()
            untilAuthorize(vm)
            vm.authorize(PIN, unlockOk)
            assertEquals(expected, vm.step.value)
        }
    }

    @Test
    fun refusals_mapToTheRightScreen() {
        val cases =
            listOf(
                ApiResult.Refused(401, "CREDENTIALS_INVALID") to DailyStep.Authorize(JEAN, 25000, Problem(DailyError.CREDENTIALS_INVALID)),
                ApiResult.Refused(409, "INSUFFICIENT_FUNDS") to DailyStep.Authorize(JEAN, 25000, Problem(DailyError.INSUFFICIENT_FUNDS)),
                ApiResult.Refused(429, "THROTTLED", 60) to DailyStep.Authorize(JEAN, 25000, Problem(DailyError.THROTTLED, 60)),
                ApiResult.Refused(403, "DECISION_REJECTED") to DailyStep.Stopped(Outcome.REJECTED),
                ApiResult.Refused(403, "KEY_REVOKED") to DailyStep.Stopped(Outcome.REJECTED_KEY),
                ApiResult.Refused(403, "ACCOUNT_FROZEN") to DailyStep.Stopped(Outcome.FROZEN),
            )
        cases.forEach { (refusal, expected) ->
            repo.nonces.addLast(NONCE_1)
            repo.transferResult = refusal
            val vm = opened()
            untilAuthorize(vm)
            vm.authorize(PIN, unlockOk)
            assertEquals(refusal.toString(), expected, vm.step.value)
        }
    }

    @Test
    fun eachAttempt_usesAFreshNonce() {
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockCancelled)
        assertEquals(Problem(DailyError.UNLOCK_CANCELLED), (vm.step.value as DailyStep.Authorize).problem)
        assertTrue("rien n'est envoyé sans signature", repo.transfers.isEmpty())
        vm.authorize(PIN, unlockOk)
        assertEquals(2, repo.calls.count { it == "nonce" })
        assertEquals(NONCE_2, repo.transfers.single().nonce)
        assertNotEquals(NONCE_1, repo.transfers.single().nonce)
    }

    @Test
    fun lostResponseAfterSending_isAnUnknownOutcome_neverResent() {
        repo.transferResult = ApiResult.Unreachable
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertEquals(DailyStep.Stopped(Outcome.UNKNOWN), vm.step.value)
        assertEquals(1, repo.transfers.size)
        repo.transferResult = ApiResult.Refused(0, "CONTRACT_VIOLATION") // réponse illisible : idem
        repo.nonces.addLast(NONCE_1)
        val vm2 = opened()
        untilAuthorize(vm2)
        vm2.authorize(PIN, unlockOk)
        assertEquals(DailyStep.Stopped(Outcome.UNKNOWN), vm2.step.value)
    }

    @Test
    fun unreachableBeforeSending_staysOnAuthorize() {
        repo.nonceResult = ApiResult.Unreachable
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertEquals(DailyStep.Authorize(JEAN, 25000, Problem(DailyError.UNREACHABLE)), vm.step.value)
        assertTrue(repo.transfers.isEmpty())
    }

    @Test
    fun expiredSessionAtNonce_returnsToUnlock() {
        repo.nonceResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertEquals(DailyStep.Unlock(ME, Problem(DailyError.SESSION_EXPIRED)), vm.step.value)
    }

    @Test
    fun aSeparatorInTheBeneficiaryId_failsClosed_beforeAnyTransfer() {
        repo.holderResult = ApiResult.Ok(JEAN.copy(beneficiaryId = "acc|000456"))
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertEquals(Problem(DailyError.UNEXPECTED), (vm.step.value as DailyStep.Authorize).problem)
        assertTrue("aucun envoi : la chaîne serait ambiguë", repo.transfers.isEmpty())
    }

    @Test
    fun doubleTap_sendsOneTransfer() {
        val gate = CompletableDeferred<Unit>()
        repo.transferGate = gate
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        assertTrue("busy pendant l'envoi", (vm.step.value as DailyStep.Authorize).busy)
        vm.authorize(PIN, unlockOk)
        vm.back() // pas de retour pendant l'appel
        gate.complete(Unit)
        assertEquals(1, repo.transfers.size)
        assertEquals(1, repo.calls.count { it == "nonce" })
        assertEquals(DailyStep.Sent(JEAN, 25000, "tr-42"), vm.step.value)
    }

    @Test
    fun cancelDeferred_signsAccountCancelTransferNonce() {
        repo.transferResult = ApiResult.Ok(TransferResponse("tr-42", "STEP_UP", "DEFERRED", "DECISION_DEFERRED", "2026-10-01T16:00:00Z", 152300))
        val vm = opened()
        untilAuthorize(vm)
        vm.authorize(PIN, unlockOk)
        vm.cancelDeferred(unlockOk)
        val action = repo.lastCancel!!
        assertEquals(NONCE_2, action.nonce)
        assertTrue(keys.verifies("acc-000123|CANCEL|tr-42|$NONCE_2".toByteArray(Charsets.UTF_8), action.signature))
        assertTrue("cancel:tr-42" in repo.calls)
        assertTrue((vm.step.value as DailyStep.Deferred).cancelled)
    }

    @Test
    fun cancelFromHome_tooLate_isExplained() {
        val op = DeferredOperation("tr-7", 30000, "677000456", "2026-10-01T16:00:00Z")
        repo.accountResult = ApiResult.Ok(ACCOUNT.copy(deferredOperations = listOf(op)))
        repo.cancelResult = ApiResult.Refused(404, "REQUEST_INVALID")
        val vm = opened()
        vm.openDeferred(op)
        assertEquals(DailyStep.Deferred("tr-7", 30000, "677000456", null, "2026-10-01T16:00:00Z"), vm.step.value)
        vm.cancelDeferred(unlockOk)
        assertEquals(Problem(DailyError.CANCEL_UNAVAILABLE), (vm.step.value as DailyStep.Deferred).problem)
    }

    @Test
    fun backNavigation_walksTheSendFlowBackwards() {
        val vm = opened()
        untilAuthorize(vm)
        vm.back()
        assertEquals(DailyStep.Confirm(JEAN, 25000), vm.step.value)
        vm.back()
        assertEquals(DailyStep.Amount(JEAN, 152300), vm.step.value)
        vm.back()
        assertTrue(vm.step.value is DailyStep.Beneficiary)
        vm.back()
        assertTrue(vm.step.value is DailyStep.Home)
    }
}
