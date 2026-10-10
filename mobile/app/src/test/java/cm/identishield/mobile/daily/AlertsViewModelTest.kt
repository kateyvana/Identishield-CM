package cm.identishield.mobile.daily

import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.data.AlertActionResponse
import cm.identishield.mobile.data.AlertList
import cm.identishield.mobile.data.AlertRepository
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.NonceResponse
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SignedAction
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.LocalUnlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.util.Base64

private const val ACCOUNT = "acc-000123"
private const val NONCE_1 = "0123456789abcdef0123456789abcdef"
private const val NONCE_2 = "fedcba9876543210fedcba9876543210"
private val POLL: Duration = Duration.ofSeconds(30)
private val SIM = Alert("al-1", "SIM_CHANGED", "2026-10-08T13:02:00Z", "La carte SIM de votre numéro a été changée.", listOf("ACK", "NOT_ME"), "OPEN")
private val INFO = Alert("al-2", "DEVICE_REBOUND", "2026-08-12T10:00:00Z", "Un nouveau téléphone a été lié à votre compte.", emptyList(), "INFO")

/** Doublure du serveur pour le flux F. */
private class FakeAlerts : AlertRepository {
    val calls = mutableListOf<String>()
    var alertsResult: ApiResult<AlertList> = ApiResult.Ok(AlertList(listOf(SIM, INFO)))
    val nonces = ArrayDeque(listOf(NONCE_1, NONCE_2))
    var nonceResult: ApiResult<NonceResponse>? = null
    var answerResult: ApiResult<AlertActionResponse>? = null
    var gate: CompletableDeferred<Unit>? = null
    val answers = mutableListOf<Pair<String, SignedAction>>()

    override suspend fun alerts(sessionToken: String) = alertsResult.also { calls += "alerts" }

    override suspend fun nonce(sessionToken: String): ApiResult<NonceResponse> {
        calls += "nonce"
        return nonceResult ?: ApiResult.Ok(NonceResponse(nonces.removeFirst()))
    }

    override suspend fun acknowledge(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ): ApiResult<AlertActionResponse> {
        calls += "ack:$alertId"
        answers += "ack" to action
        gate?.await()
        return answerResult ?: ApiResult.Ok(AlertActionResponse(alertId, "ACKNOWLEDGED", "TRUSTED"))
    }

    override suspend fun deny(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ): ApiResult<AlertActionResponse> {
        calls += "not-me:$alertId"
        answers += "not-me" to action
        return answerResult ?: ApiResult.Ok(AlertActionResponse(alertId, "NOT_ME", "FROZEN"))
    }
}

/** Puce : vraie paire EC logicielle, pour VÉRIFIER chaque signature sur les octets attendus. */
private class VerifyingKeys : DeviceKeyManager {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    fun verifies(
        payload: String,
        base64: String,
    ) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public)
        update(payload.toByteArray(Charsets.UTF_8))
        verify(Base64.getDecoder().decode(base64))
    }

    override fun hasDeviceKey() = true

    override fun generate(attestationChallenge: ByteArray): GeneratedKey = error("aucune clé n'est créée dans le flux F")

    override fun newSigner(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }

    override fun delete() = error("le flux F ne détruit jamais la clé")
}

/**
 * Flux F (ISC-MOB-SPEC §9) : interrogation au premier plan avec une attente injectée (jamais de
 * temps réel), réponses binaires signées sur le téléphone lié, alertes informatives sans action.
 */
// @covers ES-27
class AlertsViewModelTest {
    private val repo = FakeAlerts()
    private val keys = VerifyingKeys()
    private val session = SessionHolder().apply { token = "jeton"; accountId = ACCOUNT }
    private val unlockOk = LocalUnlock { s, p -> s.update(p).let { s.sign() } }
    private val unlockCancelled = LocalUnlock { _, _ -> null }

    private fun vm(sleeper: Sleeper = Sleeper { }) = AlertsViewModel(repo, keys, session, POLL, sleeper, CoroutineScope(Dispatchers.Unconfined))

    private fun loaded(): AlertsViewModel = vm().also { runBlocking { it.refresh() } }

    @Test
    fun refresh_loadsTheAlerts_pendingAreOpenWithActions() {
        val vm = loaded()
        assertEquals(listOf(SIM, INFO), vm.alerts.value)
        assertEquals("seule l'alerte ouverte avec actions est à traiter", listOf(SIM), vm.pending())
    }

    @Test
    fun poll_refreshesAtTheConfiguredInterval_withoutRealTime() =
        runBlocking {
            val slept = mutableListOf<Duration>()
            val vm = vm(Sleeper { d -> slept += d; if (slept.size == 3) throw CancellationException("fin du test") })
            val job = launch { vm.pollWhileForeground() }
            job.join()
            assertEquals(3, repo.calls.count { it == "alerts" })
            assertEquals(List(3) { POLL }, slept)
        }

    @Test
    fun itsMe_signsAccountAlertAckAlertIdNonce_andClosesTheAlert() {
        val vm = loaded()
        vm.open(SIM)
        vm.answer(notMe = false, unlockOk)
        val (kind, action) = repo.answers.single()
        assertEquals("ack", kind)
        assertEquals(NONCE_1, action.nonce)
        assertTrue(keys.verifies("$ACCOUNT|ALERT_ACK|al-1|$NONCE_1", action.signature))
        val detail = vm.screen.value as AlertsScreen.Detail
        assertEquals("ACKNOWLEDGED", detail.alert.status)
        assertTrue(detail.alert.actions.isEmpty())
        assertEquals("TRUSTED", detail.accountState)
        assertTrue("plus rien à traiter", vm.pending().isEmpty())
    }

    @Test
    fun notMe_signsAlertNotMe_andReportsTheFrozenAccount() {
        val vm = loaded()
        vm.open(SIM)
        vm.answer(notMe = true, unlockOk)
        val (kind, action) = repo.answers.single()
        assertEquals("not-me", kind)
        assertTrue(keys.verifies("$ACCOUNT|ALERT_NOT_ME|al-1|$NONCE_1", action.signature))
        val detail = vm.screen.value as AlertsScreen.Detail
        assertEquals("NOT_ME", detail.alert.status)
        assertEquals("FROZEN", detail.accountState)
    }

    @Test
    fun informativeAlert_cannotBeAnswered_nothingIsSent() {
        val vm = loaded()
        vm.open(INFO)
        vm.answer(notMe = true, unlockOk)
        vm.answer(notMe = false, unlockOk)
        assertTrue(repo.answers.isEmpty())
        assertFalse(repo.calls.contains("nonce"))
        assertEquals(AlertsScreen.Detail(INFO), vm.screen.value)
    }

    @Test
    fun cancelledUnlock_sendsNothing_andTheAlertStaysOpen() {
        val vm = loaded()
        vm.open(SIM)
        vm.answer(notMe = true, unlockCancelled)
        assertTrue(repo.answers.isEmpty())
        val detail = vm.screen.value as AlertsScreen.Detail
        assertEquals(Problem(DailyError.UNLOCK_CANCELLED), detail.problem)
        assertEquals("OPEN", detail.alert.status)
        vm.answer(notMe = true, unlockOk)
        assertEquals("nonce neuf à la seconde tentative", NONCE_2, repo.answers.single().second.nonce)
    }

    @Test
    fun doubleTap_sendsOneAnswer() {
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val vm = loaded()
        vm.open(SIM)
        vm.answer(notMe = false, unlockOk)
        assertTrue((vm.screen.value as AlertsScreen.Detail).busy)
        vm.answer(notMe = false, unlockOk)
        vm.back() // bloqué pendant l'appel
        gate.complete(Unit)
        assertEquals(1, repo.answers.size)
        assertEquals(1, repo.calls.count { it == "nonce" })
        assertTrue(vm.screen.value is AlertsScreen.Detail)
    }

    @Test
    fun expiredSession_onRefresh_raisesSessionLost_andClearsEverything() {
        val vm = loaded()
        repo.alertsResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        runBlocking { vm.refresh() }
        assertTrue(vm.sessionLost.value)
        assertTrue(vm.alerts.value.isEmpty())
        assertEquals(AlertsScreen.None, vm.screen.value)
        vm.sessionLossHandled()
        assertFalse(vm.sessionLost.value)
    }

    @Test
    fun expiredSession_onAnswer_raisesSessionLost() {
        val vm = loaded()
        vm.open(SIM)
        repo.nonceResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        vm.answer(notMe = false, unlockOk)
        assertTrue(vm.sessionLost.value)
        assertTrue(repo.answers.isEmpty())
    }

    @Test
    fun unreachable_onRefresh_keepsThePreviousAlerts_theyWait() {
        val vm = loaded()
        repo.alertsResult = ApiResult.Unreachable
        runBlocking { vm.refresh() }
        assertEquals(listOf(SIM, INFO), vm.alerts.value)
        assertFalse(vm.sessionLost.value)
    }

    @Test
    fun withoutASession_nothingIsReadNorSent() {
        session.token = null
        val vm = vm()
        runBlocking { vm.refresh() }
        vm.open(SIM)
        vm.answer(notMe = false, unlockOk)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun navigation_listDetailBack() {
        val vm = loaded()
        vm.openList()
        assertEquals(AlertsScreen.Listing, vm.screen.value)
        vm.open(SIM)
        vm.back()
        assertEquals(AlertsScreen.Listing, vm.screen.value)
        vm.back()
        assertEquals(AlertsScreen.None, vm.screen.value)
    }
}
