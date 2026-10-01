package cm.identishield.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import cm.identishield.mobile.daily.AmountScreen
import cm.identishield.mobile.daily.AuthorizeScreen
import cm.identishield.mobile.daily.BeneficiaryScreen
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.DailyViewModel
import cm.identishield.mobile.daily.DeferredScreen
import cm.identishield.mobile.daily.HistoryScreen
import cm.identishield.mobile.daily.ReviewScreen
import cm.identishield.mobile.daily.SentScreen
import cm.identishield.mobile.daily.StoppedScreen
import cm.identishield.mobile.daily.UnlockScreen
import cm.identishield.mobile.data.RetrofitDailyRepository
import cm.identishield.mobile.data.RetrofitEnrolmentRepository
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.buildMobileApi
import cm.identishield.mobile.enrolment.CheckingScreen
import cm.identishield.mobile.enrolment.ConfirmKeyScreen
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.EnrolmentViewModel
import cm.identishield.mobile.enrolment.NumberScreen
import cm.identishield.mobile.enrolment.OtpScreen
import cm.identishield.mobile.enrolment.PinScreen
import cm.identishield.mobile.enrolment.RecoveryKeyScreen
import cm.identishield.mobile.enrolment.SecuringScreen
import cm.identishield.mobile.enrolment.WelcomeScreen
import cm.identishield.mobile.launch.LaunchScreen
import cm.identishield.mobile.launch.LaunchState
import cm.identishield.mobile.launch.LaunchViewModel
import cm.identishield.mobile.security.AndroidKeystoreDeviceKeyManager
import cm.identishield.mobile.security.BiometricLocalUnlock
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.PrefsBoundDeviceStore
import cm.identishield.mobile.security.applyTouchProtection
import cm.identishield.mobile.ui.theme.IdentiShieldTheme

/** Les déverrouillages locaux, un par usage (le texte de la boîte système dit ce qui est signé). */
class Unlocks(
    val securing: LocalUnlock,
    val session: LocalUnlock,
    val transfer: LocalUnlock,
    val cancel: LocalUnlock,
)

/** FragmentActivity : exigée par BiometricPrompt (déverrouillage local lié à la signature). */
class MainActivity : FragmentActivity() {
    // Injection manuelle (ADR-037) : les dépendances sont construites ici et passées par constructeur.
    private val keys by lazy { AndroidKeystoreDeviceKeyManager() }
    private val boundDevice by lazy { PrefsBoundDeviceStore(applicationContext) }
    private val api by lazy { buildMobileApi(BuildConfig.API_BASE_URL) }
    private val launchViewModel: LaunchViewModel by viewModels { LaunchViewModel.Factory(keys) }
    private val enrolmentViewModel: EnrolmentViewModel by viewModels {
        EnrolmentViewModel.Factory(RetrofitEnrolmentRepository(api), keys, boundDevice, session)
    }
    private val dailyViewModel: DailyViewModel by viewModels {
        DailyViewModel.Factory(RetrofitDailyRepository(api), keys, boundDevice, session)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyTouchProtection() // MOB-S-07
        launchViewModel.check()
        val unlocks =
            Unlocks(
                securing = prompt(R.string.securing_prompt_title, R.string.securing_prompt_subtitle),
                session = prompt(R.string.unlock_prompt_title, R.string.unlock_prompt_subtitle),
                transfer = prompt(R.string.authorize_title, R.string.authorize_prompt_subtitle),
                cancel = prompt(R.string.cancel_prompt_title, R.string.unlock_prompt_subtitle),
            )
        setContent {
            IdentiShieldTheme {
                AppRoot(launchViewModel, enrolmentViewModel, dailyViewModel, unlocks, Build.MODEL)
            }
        }
    }

    private fun prompt(
        title: Int,
        subtitle: Int,
    ) = BiometricLocalUnlock(this, getString(title), getString(subtitle))

    companion object {
        /** Jeton de session en mémoire vive, partagé par les deux flux ; perdu avec le processus. */
        private val session = SessionHolder()
    }
}

@Composable
fun AppRoot(
    launch: LaunchViewModel,
    enrolment: EnrolmentViewModel,
    daily: DailyViewModel,
    unlocks: Unlocks,
    deviceModel: String,
) {
    val launchState by launch.state.collectAsState()
    val enrolStep by enrolment.step.collectAsState()
    val dailyStep by daily.step.collectAsState()
    if (launchState == LaunchState.Checking) {
        LaunchScreen() // E-01
        return
    }
    val enrolled = enrolStep is EnrolStep.Home // fin du flux A : la session est ouverte
    LaunchedEffect(launchState, enrolled) {
        if (launchState == LaunchState.KeyPresent || enrolled) daily.start()
    }
    val inDaily = enrolled || (launchState == LaunchState.KeyPresent && dailyStep != DailyStep.NotBound)
    if (inDaily) DailyRoot(daily, dailyStep, unlocks, deviceModel) else EnrolmentRoot(enrolment, enrolStep, unlocks)
}

@Composable
private fun EnrolmentRoot(
    enrolment: EnrolmentViewModel,
    step: EnrolStep,
    unlocks: Unlocks,
) {
    BackHandler(enabled = step is EnrolStep.Number || step is EnrolStep.Pin || step is EnrolStep.Otp) {
        enrolment.back()
    }
    when (step) {
        EnrolStep.Welcome -> WelcomeScreen(onStart = enrolment::begin)
        is EnrolStep.Number -> NumberScreen(step, enrolment::submitNumber, enrolment::back)
        is EnrolStep.Pin -> PinScreen(step, enrolment::submitPin, enrolment::back)
        is EnrolStep.Checking -> CheckingScreen()
        is EnrolStep.Otp ->
            OtpScreen(step, BuildConfig.OTP_RESEND_DELAY_SECONDS, enrolment::submitOtp, enrolment::resendOtp, enrolment::back)
        is EnrolStep.Securing ->
            SecuringScreen(step, onSecure = { enrolment.secureDevice(unlocks.securing) }, onRestart = enrolment::begin)
        is EnrolStep.RecoveryKey -> RecoveryKeyScreen(step, enrolment::keyWrittenDown)
        is EnrolStep.Confirm -> ConfirmKeyScreen(step, enrolment::confirmCharacters)
        is EnrolStep.Home -> CheckingScreen() // relais vers le flux B (AppRoot)
    }
}

@Composable
private fun DailyRoot(
    daily: DailyViewModel,
    step: DailyStep,
    unlocks: Unlocks,
    deviceModel: String,
) {
    val canGoBack =
        when (step) {
            is DailyStep.Authorize -> !step.busy
            is DailyStep.Beneficiary, is DailyStep.Amount, is DailyStep.Confirm, is DailyStep.History,
            is DailyStep.Sent, is DailyStep.Deferred, is DailyStep.Stopped,
            -> true
            else -> false
        }
    BackHandler(enabled = canGoBack) { daily.back() }
    when (step) {
        DailyStep.Starting, DailyStep.NotBound -> LaunchScreen()
        is DailyStep.Unlock -> UnlockScreen(step) { pin -> daily.unlock(pin, unlocks.session) }
        DailyStep.Opening -> CheckingScreen() // E-04
        is DailyStep.Home ->
            DailyHomeScreen(
                step.account,
                deviceModel,
                step.transactions,
                step.problem,
                onSend = daily::startSend,
                onHistory = daily::openHistory,
                onDeferred = daily::openDeferred,
            )
        is DailyStep.History -> HistoryScreen(step, onBack = daily::back, onHome = daily::goHome, onSend = daily::startSend)
        is DailyStep.Beneficiary -> BeneficiaryScreen(step, daily::chooseBeneficiary, daily::back)
        is DailyStep.Amount -> AmountScreen(step, BuildConfig.QUICK_AMOUNTS_XAF.toList(), daily::submitAmount, daily::back)
        is DailyStep.Confirm -> ReviewScreen(step, daily::confirmTransfer, onCancel = daily::goHome, onBack = daily::back)
        is DailyStep.Authorize -> AuthorizeScreen(step, { pin -> daily.authorize(pin, unlocks.transfer) }, daily::back)
        is DailyStep.Sent -> SentScreen(step, deviceModel, daily::goHome)
        is DailyStep.Deferred -> DeferredScreen(step, onCancel = { daily.cancelDeferred(unlocks.cancel) }, onHome = daily::goHome)
        is DailyStep.Stopped -> StoppedScreen(step, onHome = daily::goHome, onHistory = daily::openHistory)
    }
}
