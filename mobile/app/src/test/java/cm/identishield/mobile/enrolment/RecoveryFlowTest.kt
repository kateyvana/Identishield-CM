package cm.identishield.mobile.enrolment

import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.BindResponse
import cm.identishield.mobile.data.CompleteRequest
import cm.identishield.mobile.data.ConfirmRequest
import cm.identishield.mobile.data.EnrolmentRepository
import cm.identishield.mobile.data.RecoveryCodeIssued
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SessionResponse
import cm.identishield.mobile.data.StartResponse
import cm.identishield.mobile.security.BoundDevice
import cm.identishield.mobile.security.BoundDeviceStore
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.InsecureKeyStorageException
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.MemoryThrottleStore
import cm.identishield.mobile.security.MutableClock
import cm.identishield.mobile.security.RecoveryThrottle
import cm.identishield.mobile.security.ScreenLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.util.Base64

private const val NUMBER = "677000123"
private const val PIN = "1234"
private const val OLD_CODE = "K7PQ3M" // CODE_LENGTH = 6 (décision du 2026-10-10)
private const val NEW_CODE = "Q4TN8X"
private const val DEVICE_ID = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"
private val CHALLENGE = ByteArray(16) { (it + 1).toByte() }

/** Doublure du serveur pour le flux D. */
private class FakeRecoveryServer : EnrolmentRepository {
    val calls = mutableListOf<String>()
    val codesSent = mutableListOf<String>()
    var startResult: ApiResult<StartResponse> = ApiResult.Ok(StartResponse(next = "RECOVERY", enrolmentId = "e-d"))
    val recoverResults = ArrayDeque<ApiResult<BindResponse>>()
    var completeResult: ApiResult<RecoveryCodeIssued> =
        ApiResult.Ok(RecoveryCodeIssued("c-d", NEW_CODE, deviceId = DEVICE_ID))

    override suspend fun start(
        msisdn: String,
        pin: String,
    ) = startResult.also { calls += "start" }

    override suspend fun verifyOtp(
        enrolmentId: String,
        otp: String,
    ): ApiResult<BindResponse> = error("aucun OTP au ré-enrôlement (SPEC §8.1)")

    override suspend fun recover(
        enrolmentId: String,
        recoveryCode: String,
    ): ApiResult<BindResponse> {
        calls += "recover"
        codesSent += recoveryCode
        return recoverResults.removeFirstOrNull()
            ?: ApiResult.Ok(BindResponse("BIND", Base64.getEncoder().encodeToString(CHALLENGE)))
    }

    override suspend fun complete(request: CompleteRequest) = completeResult.also { calls += "complete" }

    override suspend fun confirm(request: ConfirmRequest): ApiResult<SessionResponse> =
        ApiResult.Ok(SessionResponse("jeton-d", 300)).also { calls += "confirm" }

    override suspend fun account(sessionToken: String) =
        ApiResult.Ok(Account("acc-000123", NUMBER, "Titulaire fictif", 1000, "TRUSTED", emptyList())).also { calls += "account" }
}

/** Puce : journal ORDONNÉ des appels, pour prouver « supprimer AVANT de créer ». */
private class LoggingKeys(
    var insecure: Boolean = false,
) : DeviceKeyManager {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val log = mutableListOf<String>()
    var generatedWith: ByteArray? = null

    override fun hasDeviceKey() = generatedWith != null

    override fun generate(attestationChallenge: ByteArray): GeneratedKey {
        log += "generate"
        if (insecure) throw InsecureKeyStorageException()
        generatedWith = attestationChallenge
        return GeneratedKey("cHVi", listOf("Y2VydA=="))
    }

    override fun newSigner(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }

    override fun delete() {
        log += "delete"
        generatedWith = null
    }
}

private class Remembered : BoundDeviceStore {
    var device: BoundDevice? = null

    override fun load() = device

    override fun save(device: BoundDevice) {
        this.device = device
    }
}

/**
 * Flux D : ré-enrôlement par clé de récupération (ISC-MOB-SPEC §8). Le serveur décide ; essais
 * libres puis délai croissant sur CET appareil (horloge injectée) ; ancienne clé détruite AVANT la
 * nouvelle ; la clé saisie n'est conservée nulle part.
 */
// @covers ES-21
// @covers ES-22
// @covers ES-25
class RecoveryFlowTest {
    private val server = FakeRecoveryServer()
    private val keys = LoggingKeys()
    private val clock = MutableClock()
    private val throttleStore = MemoryThrottleStore()
    private val base = Duration.ofSeconds(BuildConfig.LOCK_BASE_SECONDS)
    private val throttle =
        RecoveryThrottle(throttleStore, clock, BuildConfig.REENROL_ATTEMPTS, base, Duration.ofSeconds(BuildConfig.LOCK_MAX_SECONDS))
    private val remembered = Remembered()
    private val session = SessionHolder()
    private val unlockOk = LocalUnlock { s, p -> s.update(p).let { s.sign() } }

    private fun vm(
        withThrottle: Boolean = true,
        screenLock: ScreenLock = ScreenLock { true },
    ) = EnrolmentViewModel(
        server,
        keys,
        CoroutineScope(Dispatchers.Unconfined),
        boundDevice = remembered,
        session = session,
        throttle = if (withThrottle) throttle else null,
        screenLock = screenLock,
    )

    /** D3 vaut aussi pour le flux D : sans verrou d'écran, ni suppression ni création de clé. */
    @Test
    fun d3_fluxD_requiresTheScreenLock_beforeTouchingAnyKey() {
        val vm = vm(screenLock = ScreenLock { false })
        untilRecovery(vm)
        vm.submitRecoveryCode(OLD_CODE)
        assertEquals(EnrolStep.ScreenLockRequired(), vm.step.value)
        vm.secureDevice(unlockOk)
        assertTrue("ni delete ni generate sans verrou", keys.log.isEmpty())
    }

    private fun untilRecovery(vm: EnrolmentViewModel) {
        vm.beginWithNumber(NUMBER)
        vm.submitPin(PIN)
    }

    private val invalid = ApiResult.Refused(401, "RECOVERY_INVALID")

    @Test
    fun theServerSaysRecovery_theAppNeverDecides_andNoKeyIsMade() {
        val vm = vm()
        vm.beginWithNumber(NUMBER)
        assertEquals("E-27a reprend au PIN, numéro mémorisé", EnrolStep.Pin(NUMBER), vm.step.value)
        vm.submitPin(PIN)
        assertEquals(EnrolStep.Recovery(), vm.step.value)
        vm.secureDevice(unlockOk) // tentative prématurée : ignorée (ES-25)
        assertTrue(keys.log.isEmpty())
    }

    @Test
    fun aWrongKey_firstTime_isTheSameMessageForEveryone() {
        server.recoverResults += invalid
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode("AAAA-BBBB-CCCC-DDDD-EEEE")
        assertEquals(EnrolStep.Recovery(EnrolError.RECOVERY_INVALID), vm.step.value)
        assertTrue(keys.log.isEmpty())
    }

    @Test
    fun afterTheFreeAttempts_thisDeviceWaits_andSendsNothingUntilTheEnd() {
        repeat(BuildConfig.REENROL_ATTEMPTS + 1) { server.recoverResults += invalid }
        val vm = vm()
        untilRecovery(vm)
        repeat(BuildConfig.REENROL_ATTEMPTS) { vm.submitRecoveryCode("AAAA") }
        assertEquals(EnrolStep.RecoveryWait(clock.now.plus(base)), vm.step.value)
        val sent = server.calls.count { it == "recover" }
        // Application relancée pendant l'attente : E-21 directement, sans requête.
        val restarted = vm()
        untilRecovery(restarted)
        assertEquals(EnrolStep.RecoveryWait(clock.now.plus(base)), restarted.step.value)
        restarted.submitRecoveryCode("AAAA")
        // Pendant l'attente : aucune requête, même si l'écran E-20 était réaffiché.
        clock.advance(base.minusSeconds(1))
        vm.recoveryWaitOver()
        assertTrue("toujours en attente", vm.step.value is EnrolStep.RecoveryWait)
        assertEquals(sent, server.calls.count { it == "recover" })
        clock.advance(Duration.ofSeconds(1))
        vm.recoveryWaitOver()
        assertEquals(EnrolStep.Recovery(), vm.step.value)
        vm.submitRecoveryCode("AAAA")
        assertEquals("le délai double", EnrolStep.RecoveryWait(clock.now.plus(base.multipliedBy(2))), vm.step.value)
    }

    @Test
    fun aServer429_showsItsWait_withoutCountingAFailure() {
        server.recoverResults += ApiResult.Refused(429, "THROTTLED", 600)
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode("AAAA")
        assertEquals(EnrolStep.RecoveryWait(clock.now.plusSeconds(600)), vm.step.value)
        assertEquals(0, throttleStore.state.failures)
    }

    @Test
    fun aValidKey_deletesTheOldKeyBeforeCreatingTheNew_thenE22_thenTheNewKey() {
        server.recoverResults += invalid // un échec d'abord : le succès doit remettre le compteur à zéro
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode("AAAA")
        assertEquals(1, throttleStore.state.failures)
        vm.submitRecoveryCode(OLD_CODE.lowercase().chunked(3).joinToString("-"))
        assertEquals("normalisée : majuscules, sans tirets", listOf("AAAA", OLD_CODE), server.codesSent)
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY), vm.step.value)
        vm.secureDevice(unlockOk)
        assertEquals(listOf("delete", "generate"), keys.log)
        assertTrue("la nouvelle clé naît avec le défi du serveur", CHALLENGE.contentEquals(keys.generatedWith))
        assertEquals(EnrolStep.Rebound(NUMBER), vm.step.value)
        assertEquals(BoundDevice(NUMBER, DEVICE_ID), remembered.device)
        vm.showNewKey()
        assertEquals(EnrolStep.RecoveryKey(NEW_CODE, lastDisplay = false), vm.step.value)
        vm.keyWrittenDown()
        vm.confirmCode(NEW_CODE)
        assertTrue(vm.step.value is EnrolStep.Home)
        assertEquals("jeton-d", session.token)
        assertFalse(vm.holdsSecrets())
        assertEquals("compteur de l'appareil remis à zéro", 0, throttleStore.state.failures)
    }

    @Test
    fun theRecoveryCode_isNeverStored_onlyWhatIsTypedIsSent() {
        server.recoverResults += invalid
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode(OLD_CODE)
        vm.submitRecoveryCode(NEW_CODE) // deuxième saisie : seule la saisie du moment part au serveur
        assertEquals(listOf(OLD_CODE, NEW_CODE), server.codesSent)
        throttleStore.saved.forEach { assertFalse("mémoire du délai : jamais la clé", it.toString().contains(OLD_CODE)) }
        assertFalse("préférences : jamais la clé", remembered.device.toString().contains(OLD_CODE))
        // Seul le PIN est encore en mémoire ici (comme au flux A) ; il est effacé à l'accueil :
        vm.secureDevice(unlockOk)
        vm.showNewKey()
        vm.keyWrittenDown()
        vm.confirmCode(NEW_CODE)
        assertFalse("aucun secret ne survit à l'arrivée sur l'accueil", vm.holdsSecrets())
    }

    @Test
    fun aSoftwareOnlyChip_isRefused_onReenrolmentToo() {
        keys.insecure = true
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode(OLD_CODE)
        vm.secureDevice(unlockOk)
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY, EnrolError.DEVICE_REFUSED), vm.step.value)
        assertFalse(server.calls.contains("complete"))
    }

    @Test
    fun withoutAThrottle_theKeyIsNeverSent_failClosed() {
        val vm = vm(withThrottle = false)
        untilRecovery(vm)
        vm.submitRecoveryCode(OLD_CODE)
        assertEquals(EnrolStep.Recovery(EnrolError.UNEXPECTED), vm.step.value)
        assertFalse(server.calls.contains("recover"))
    }

    @Test
    fun frozenReenrolment_andLostKey() {
        server.recoverResults += ApiResult.Refused(403, "ACCOUNT_FROZEN")
        val vm = vm()
        untilRecovery(vm)
        vm.submitRecoveryCode(OLD_CODE)
        assertEquals(EnrolStep.Recovery(EnrolError.REENROL_FROZEN), vm.step.value)
        vm.lostKey()
        assertEquals(EnrolStep.KeyLost, vm.step.value)
        vm.back()
        assertEquals(EnrolStep.Recovery(EnrolError.REENROL_FROZEN), vm.step.value)
    }
}
