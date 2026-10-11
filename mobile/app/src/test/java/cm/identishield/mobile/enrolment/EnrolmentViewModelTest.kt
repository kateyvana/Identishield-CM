package cm.identishield.mobile.enrolment

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.ProviderException
import java.security.Signature
import java.util.Base64

private const val NEW_NUMBER = "677000200"
private const val PIN = "1234"
private const val OTP = "246810"
private const val CODE = "K7M2QX" // CODE_LENGTH = 6 (décision du 2026-10-10)
private val CHALLENGE = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
private val ACCOUNT = Account("acc-000200", NEW_NUMBER, "Compte de test NEUF", 0, "TRUSTED", emptyList())

/** Doublure du serveur : réponses programmées, appels enregistrés. */
private class FakeRepository : EnrolmentRepository {
    var startResult: ApiResult<StartResponse> = ApiResult.Ok(StartResponse(next = "OTP", enrolmentId = "e-1"))
    var otpResult: ApiResult<BindResponse> =
        ApiResult.Ok(BindResponse("BIND", Base64.getEncoder().encodeToString(CHALLENGE)))
    var completeResult: ApiResult<RecoveryCodeIssued> = ApiResult.Ok(RecoveryCodeIssued("c-1", CODE))
    val confirmResults = ArrayDeque<ApiResult<SessionResponse>>()
    val calls = mutableListOf<String>()
    var lastComplete: CompleteRequest? = null
    var lastConfirm: ConfirmRequest? = null
    var otpGate: CompletableDeferred<Unit>? = null // retient la réponse OTP (double appui)

    override suspend fun start(
        msisdn: String,
        pin: String,
    ) = startResult.also { calls += "start" }

    override suspend fun verifyOtp(
        enrolmentId: String,
        otp: String,
    ): ApiResult<BindResponse> {
        calls += "otp"
        otpGate?.await()
        return otpResult
    }

    override suspend fun complete(request: CompleteRequest) = completeResult.also {
        calls += "complete"
        lastComplete = request
    }

    override suspend fun confirm(request: ConfirmRequest): ApiResult<SessionResponse> {
        calls += "confirm"
        lastConfirm = request
        return confirmResults.removeFirstOrNull() ?: ApiResult.Ok(SessionResponse("jeton", 300))
    }

    override suspend fun account(sessionToken: String) = ApiResult.Ok(ACCOUNT).also { calls += "account" }
}

/** Doublure de la puce : une vraie paire EC logicielle de test, et le journal des appels. */
private class FakeKeys(
    var insecure: Boolean = false,
) : DeviceKeyManager {
    var generateFailure: RuntimeException? = null // panne du Keystore après création de la paire
    var signerFailure: Exception? = null // ex. clé invalidée par une nouvelle biométrie
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val publicKey: java.security.PublicKey get() = pair.public
    var generatedWith: ByteArray? = null
    var generateCalls = 0
    var deleted = 0

    override fun hasDeviceKey() = generatedWith != null

    override fun generate(attestationChallenge: ByteArray): GeneratedKey {
        generateCalls++
        if (insecure) throw InsecureKeyStorageException()
        generatedWith = attestationChallenge
        generateFailure?.let { throw it }
        return GeneratedKey("cHVi", listOf("Y2VydA=="))
    }

    override fun newSigner(): Signature = signerFailure?.let { throw it } ?: Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }

    override fun delete() {
        deleted++
        generatedWith = null
    }
}

class EnrolmentViewModelTest {
    private val repo = FakeRepository()
    private val keys = FakeKeys()
    private val signs = { s: Signature, p: ByteArray -> s.update(p).let { s.sign() } }
    private val unlockOk = cm.identishield.mobile.security.LocalUnlock { s, p -> signs(s, p) }
    private val unlockCancelled = cm.identishield.mobile.security.LocalUnlock { _, _ -> null }

    private fun vm() = EnrolmentViewModel(repo, keys, CoroutineScope(Dispatchers.Unconfined))

    private fun untilOtp(vm: EnrolmentViewModel) {
        vm.begin()
        vm.submitNumber(NEW_NUMBER)
        vm.submitPin(PIN)
    }

    @Test
    fun flowA_happyPath_endsOnHome_withoutAnySecretLeft() {
        val vm = vm()
        assertEquals(EnrolStep.Welcome, vm.step.value)
        untilOtp(vm)
        assertEquals(EnrolStep.Otp(NEW_NUMBER), vm.step.value)
        vm.submitOtp(OTP)
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY), vm.step.value)
        vm.secureDevice(unlockOk)
        assertArrayEquals("la clé naît avec le défi du serveur", CHALLENGE, keys.generatedWith)
        assertEquals(EnrolStep.RecoveryKey(CODE, lastDisplay = false), vm.step.value)
        vm.keyWrittenDown()
        assertEquals("E-09 ne reçoit que la fin du code (rappel masqué)", EnrolStep.Confirm("QX"), vm.step.value)
        vm.confirmCode(CODE)
        assertEquals(EnrolStep.Home(ACCOUNT), vm.step.value)
        assertEquals(listOf("start", "otp", "complete", "confirm", "account"), repo.calls)
        assertFalse("PIN, clé de récupération et défi sont effacés (P-8, P-9)", vm.holdsSecrets())
    }

    @Test
    fun signatureSentToServer_isTheSignatureOfTheAttestationChallenge() {
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        val sent = repo.lastComplete!!
        val verifier =
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(keys.publicKey)
                update(CHALLENGE)
            }
        assertTrue("signature du défi, vérifiable par la clé publique", verifier.verify(Base64.getDecoder().decode(sent.challengeSignature)))
        assertEquals("cHVi", sent.publicKey)
        assertEquals(listOf("Y2VydA=="), sent.attestationChain)
        assertEquals("e-1", sent.enrolmentId)
    }

    // @covers ES-25
    @Test
    fun es25_keyIsNeverCreatedBeforeTheServerValidatesTheOtp() {
        repo.otpResult = ApiResult.Refused(401, "OTP_INVALID")
        val vm = vm()
        untilOtp(vm)
        vm.secureDevice(unlockOk) // tentative prématurée : ignorée
        vm.submitOtp("000000")
        vm.secureDevice(unlockOk) // toujours sur E-05 : ignorée
        assertEquals(0, keys.generateCalls)
        assertEquals(EnrolStep.Otp(NEW_NUMBER, EnrolError.OTP_INVALID), vm.step.value)
        assertFalse(repo.calls.contains("complete"))
    }

    @Test
    fun es22_unknownAccountAndWrongPinShowTheSameMessage() {
        repo.startResult = ApiResult.Refused(401, "CREDENTIALS_INVALID")
        val vm = vm()
        untilOtp(vm)
        assertEquals(EnrolStep.Pin(NEW_NUMBER, EnrolError.CREDENTIALS_INVALID), vm.step.value)
    }

    @Test
    fun otpExpired_isShownOnTheOtpScreen() {
        repo.otpResult = ApiResult.Refused(410, "OTP_EXPIRED")
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        assertEquals(EnrolStep.Otp(NEW_NUMBER, EnrolError.OTP_EXPIRED), vm.step.value)
    }

    @Test
    fun numberFormat_isCheckedLocally_rangeByTheServer() {
        val vm = vm()
        vm.begin()
        vm.submitNumber("67700020") // 8 chiffres : forme refusée localement, aucun appel
        assertEquals(EnrolStep.Number(EnrolError.NUMBER_INVALID), vm.step.value)
        vm.submitNumber("67700020a")
        assertEquals(EnrolStep.Number(EnrolError.NUMBER_INVALID), vm.step.value)
        assertTrue(repo.calls.isEmpty())
        vm.submitNumber("677 000 200") // espaces tolérés
        assertEquals(EnrolStep.Pin(NEW_NUMBER), vm.step.value)
        repo.startResult = ApiResult.Refused(400, "REQUEST_INVALID") // plage refusée par le serveur
        vm.submitPin(PIN)
        assertEquals(EnrolStep.Number(EnrolError.NUMBER_INVALID), vm.step.value)
    }

    @Test
    fun alreadyBoundAccount_isNotEnrolledHere_andNoKeyIsCreated() {
        repo.startResult = ApiResult.Ok(StartResponse(next = "RECOVERY", enrolmentId = "e-2"))
        val vm = vm()
        untilOtp(vm)
        assertEquals(EnrolStep.Recovery(), vm.step.value) // M-08 : flux D (E-20), et non plus un message
        assertEquals(0, keys.generateCalls)
    }

    @Test
    fun cancelledUnlock_destroysTheLocalKey_andSendsNothing() {
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockCancelled)
        assertEquals(EnrolStep.Securing(SecuringStage.UNLOCKING, EnrolError.UNLOCK_CANCELLED), vm.step.value)
        assertEquals(1, keys.deleted)
        assertFalse(keys.hasDeviceKey())
        assertFalse(repo.calls.contains("complete"))
    }

    @Test
    fun lostConnectionDuringRegistration_destroysTheLocalKey() {
        repo.completeResult = ApiResult.Unreachable
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertEquals(EnrolStep.Securing(SecuringStage.REGISTERING, EnrolError.UNREACHABLE), vm.step.value)
        assertFalse("pas de clé orpheline (SPEC §5.3)", keys.hasDeviceKey())
    }

    @Test
    fun softwareOnlyKey_isRefused_mobS02() {
        val insecure = FakeKeys(insecure = true)
        val vm = EnrolmentViewModel(repo, insecure, CoroutineScope(Dispatchers.Unconfined))
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY, EnrolError.DEVICE_REFUSED), vm.step.value)
        assertFalse(repo.calls.contains("complete"))
    }

    @Test
    fun wrongConfirmation_redisplaysTheKeyOnce_thenStaysOnConfirm() {
        repo.confirmResults += ApiResult.Refused(401, "RECOVERY_INVALID")
        repo.confirmResults += ApiResult.Refused(401, "RECOVERY_INVALID")
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        vm.keyWrittenDown()
        vm.confirmCode("ZZZZZZ")
        assertEquals(EnrolStep.RecoveryKey(CODE, lastDisplay = true), vm.step.value)
        vm.keyWrittenDown()
        vm.confirmCode("ZZZZZZ")
        assertEquals(EnrolStep.Confirm("QX", EnrolError.CONFIRM_MISMATCH), vm.step.value)
    }

    @Test
    fun confirmation_sendsTheWholeCode_normalised() {
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        vm.keyWrittenDown()
        vm.confirmCode(CODE.lowercase().chunked(3).joinToString("-"))
        val sent = repo.lastConfirm!!
        assertEquals("c-1", sent.enrolmentId)
        assertEquals("majuscules, sans tiret : le code complet, une fois", CODE, sent.recoveryCode)
    }

    @Test
    fun resend_restartsTheEnrolment_andRestartsTheCountdown() {
        val vm = vm()
        untilOtp(vm)
        vm.resendOtp()
        assertEquals(EnrolStep.Otp(NEW_NUMBER, resendGeneration = 1), vm.step.value)
        assertEquals(listOf("start", "start"), repo.calls)
    }

    @Test
    fun backNavigation_neverLeavesTheBindingFlow() {
        val vm = vm()
        untilOtp(vm)
        vm.back()
        assertEquals(EnrolStep.Pin(NEW_NUMBER), vm.step.value)
        vm.submitPin(PIN)
        vm.submitOtp(OTP)
        vm.back() // pendant la sécurisation : aucun retour
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY), vm.step.value)
    }

    @Test
    fun doubleTap_sendsOneRequest_soAUsedOtpCannotUndoTheBinding() {
        val gate = CompletableDeferred<Unit>()
        repo.otpGate = gate
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.submitOtp(OTP) // second appui pendant l'appel : l'OTP est à usage unique
        gate.complete(Unit)
        assertEquals(1, repo.calls.count { it == "otp" })
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY), vm.step.value)
    }

    @Test
    fun keystoreFailure_isShownOnE07_withoutCrash_andLeavesNoKey() {
        keys.generateFailure = ProviderException("panne du Keystore")
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertEquals(EnrolStep.Securing(SecuringStage.CREATING_KEY, EnrolError.UNEXPECTED), vm.step.value)
        assertFalse("pas de clé orpheline : E-01 croirait l'appareil lié", keys.hasDeviceKey())
        assertFalse(repo.calls.contains("complete"))
    }

    @Test
    fun invalidatedKeyAtSigning_isShownOnE07_withoutCrash_andLeavesNoKey() {
        keys.signerFailure = InvalidKeyException("clé invalidée")
        val vm = vm()
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertEquals(EnrolStep.Securing(SecuringStage.UNLOCKING, EnrolError.UNEXPECTED), vm.step.value)
        assertFalse(keys.hasDeviceKey())
        assertFalse(repo.calls.contains("complete"))
    }

    private class RecordingStore : BoundDeviceStore {
        val saved = mutableListOf<BoundDevice>()

        override fun load() = saved.lastOrNull()

        override fun save(device: BoundDevice) {
            saved += device
        }
    }

    @Test
    fun endOfFlowA_remembersNumberAndDeviceId_andHandsTheSessionToFlowB() {
        repo.completeResult = ApiResult.Ok(RecoveryCodeIssued("c-1", CODE, deviceId = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"))
        val store = RecordingStore()
        val session = SessionHolder()
        val vm = EnrolmentViewModel(repo, keys, CoroutineScope(Dispatchers.Unconfined), store, session)
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertEquals("mémorisés dès la liaison", listOf(BoundDevice(NEW_NUMBER, "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")), store.saved)
        vm.keyWrittenDown()
        vm.confirmCode(CODE)
        assertEquals("jeton", session.token)
        assertFalse(vm.holdsSecrets())
    }

    @Test
    fun withoutDeviceIdFromTheServer_nothingIsRemembered() {
        val store = RecordingStore()
        val vm = EnrolmentViewModel(repo, keys, CoroutineScope(Dispatchers.Unconfined), store, SessionHolder())
        untilOtp(vm)
        vm.submitOtp(OTP)
        vm.secureDevice(unlockOk)
        assertTrue(store.saved.isEmpty())
    }
}
