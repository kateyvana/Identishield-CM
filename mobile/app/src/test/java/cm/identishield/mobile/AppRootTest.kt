package cm.identishield.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.daily.AlertsViewModel
import cm.identishield.mobile.daily.DailyViewModel
import cm.identishield.mobile.daily.Sleeper
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.AlertActionResponse
import cm.identishield.mobile.data.AlertList
import cm.identishield.mobile.data.AlertRepository
import cm.identishield.mobile.data.ApiResult
import cm.identishield.mobile.data.BindResponse
import cm.identishield.mobile.data.CancelResponse
import cm.identishield.mobile.data.ChallengeResponse
import cm.identishield.mobile.data.CompleteRequest
import cm.identishield.mobile.data.ConfirmRequest
import cm.identishield.mobile.data.DailyRepository
import cm.identishield.mobile.data.EnrolmentRepository
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.data.NonceResponse
import cm.identishield.mobile.data.RecoveryCodeIssued
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.SessionRequest
import cm.identishield.mobile.data.SessionResponse
import cm.identishield.mobile.data.SignedAction
import cm.identishield.mobile.data.StartResponse
import cm.identishield.mobile.data.TransactionList
import cm.identishield.mobile.data.TransferRequest
import cm.identishield.mobile.data.TransferResponse
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.EnrolmentViewModel
import cm.identishield.mobile.launch.LaunchViewModel
import cm.identishield.mobile.security.BoundDevice
import cm.identishield.mobile.security.BoundDeviceStore
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.GeneratedKey
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.util.Base64

private const val NUMBER = "677000200"
private const val DEVICE_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
private const val CODE = "K7M2QX"
private val ACCOUNT = Account("acc-000200", NUMBER, "Compte de test NEUF", 0, "TRUSTED", emptyList())

/** Serveur du flux A, réduit à ce que le parcours jusqu'à E-08 exige. */
private class FlowAServer : EnrolmentRepository {
    override suspend fun start(
        msisdn: String,
        pin: String,
    ) = ApiResult.Ok(StartResponse(next = "OTP", enrolmentId = "e-1"))

    override suspend fun verifyOtp(
        enrolmentId: String,
        otp: String,
    ) = ApiResult.Ok(BindResponse("BIND", Base64.getEncoder().encodeToString(ByteArray(16) { 1 })))

    override suspend fun complete(request: CompleteRequest) = ApiResult.Ok(RecoveryCodeIssued("c-1", CODE, deviceId = DEVICE_ID))

    override suspend fun confirm(request: ConfirmRequest) = ApiResult.Ok(SessionResponse("jeton", 300))

    override suspend fun account(sessionToken: String) = ApiResult.Ok(ACCOUNT)
}

private class DailyServer : DailyRepository {
    override suspend fun challenge(deviceId: String) = ApiResult.Ok(ChallengeResponse("defi"))

    override suspend fun openSession(request: SessionRequest) = ApiResult.Ok(SessionResponse("jeton", 300))

    override suspend fun account(sessionToken: String) = ApiResult.Ok(ACCOUNT)

    override suspend fun transactions(sessionToken: String) = ApiResult.Ok(TransactionList(emptyList()))

    override suspend fun holder(
        sessionToken: String,
        msisdn: String,
    ) = ApiResult.Ok(Holder(msisdn, "acc-x", "X", newBeneficiary = true))

    override suspend fun nonce(sessionToken: String) = ApiResult.Ok(NonceResponse("0123456789abcdef0123456789abcdef"))

    override suspend fun transfer(
        sessionToken: String,
        request: TransferRequest,
    ) = ApiResult.Ok(TransferResponse("tr-1", "ALLOW", "EXECUTED", balanceXaf = 0))

    override suspend fun cancel(
        sessionToken: String,
        transferId: String,
        action: SignedAction,
    ) = ApiResult.Ok(CancelResponse(transferId, "CANCELLED"))
}

private class AlertServer : AlertRepository {
    override suspend fun alerts(sessionToken: String) = ApiResult.Ok(AlertList(emptyList()))

    override suspend fun nonce(sessionToken: String) = ApiResult.Ok(NonceResponse("0123456789abcdef0123456789abcdef"))

    override suspend fun acknowledge(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ) = ApiResult.Ok(AlertActionResponse(alertId, "ACKNOWLEDGED", "TRUSTED"))

    override suspend fun deny(
        sessionToken: String,
        alertId: String,
        action: SignedAction,
    ) = ApiResult.Ok(AlertActionResponse(alertId, "NOT_ME", "FROZEN"))
}

private class Keys : DeviceKeyManager {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    var present = false

    override fun hasDeviceKey() = present

    override fun generate(attestationChallenge: ByteArray): GeneratedKey {
        present = true
        return GeneratedKey("cHVi", listOf("Y2VydA=="))
    }

    override fun newSigner(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }

    override fun delete() {
        present = false
    }
}

private class Store : BoundDeviceStore {
    var device: BoundDevice? = null

    override fun load() = device

    override fun save(device: BoundDevice) {
        this.device = device
    }
}

/**
 * D4 (b) : la rotation recrée l'activité, qui relance la vérification de la clé (E-01). La clé
 * existe depuis E-07, mais l'enrôlement n'est pas fini : l'application DOIT rester sur E-08, jamais
 * sauter à E-10 (sinon la copie de la clé n'est jamais confirmée).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class AppRootTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recreatedActivityDuringE08_staysOnTheEnrolment_neverJumpsToE10() {
        val keys = Keys()
        val store = Store()
        val session = SessionHolder()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sign = LocalUnlock { s, p -> s.update(p).let { s.sign() } }
        val enrolment = EnrolmentViewModel(FlowAServer(), keys, scope, boundDevice = store, session = session)
        enrolment.begin()
        enrolment.submitNumber(NUMBER)
        enrolment.submitPin("1234")
        enrolment.submitOtp("246810")
        enrolment.secureDevice(sign)
        assertEquals(EnrolStep.RecoveryKey(CODE, lastDisplay = false), enrolment.step.value)
        // L'activité est recréée : E-01 revérifie la clé, qui existe maintenant.
        val launch = LaunchViewModel { keys.hasDeviceKey() }.apply { check() }
        val daily = DailyViewModel(DailyServer(), keys, store, session, scope)
        val alerts = AlertsViewModel(AlertServer(), keys, session, Duration.ofSeconds(30), Sleeper { }, scope)
        val unlocks = Unlocks(sign, sign, sign, sign, sign)
        compose.setContent {
            IdentiShieldTheme {
                AppRoot(launch, enrolment, daily, alerts, unlocks, "Test", boundMsisdn = { store.device?.msisdn }, onQuit = {}, onOpenSecuritySettings = {})
            }
        }
        compose.onNodeWithTag("E-08").assertIsDisplayed()
    }
}
