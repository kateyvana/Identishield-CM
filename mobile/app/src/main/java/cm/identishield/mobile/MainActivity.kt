package cm.identishield.mobile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import cm.identishield.mobile.daily.AlertDetailScreen
import cm.identishield.mobile.daily.AlertsListScreen
import cm.identishield.mobile.daily.AlertsScreen
import cm.identishield.mobile.daily.AlertsViewModel
import cm.identishield.mobile.daily.AmountScreen
import cm.identishield.mobile.daily.AuthorizeScreen
import cm.identishield.mobile.daily.BeneficiaryScreen
import cm.identishield.mobile.daily.DailyError
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.DailyViewModel
import cm.identishield.mobile.daily.DeferredScreen
import cm.identishield.mobile.daily.HistoryScreen
import cm.identishield.mobile.daily.ReviewScreen
import cm.identishield.mobile.daily.SentScreen
import cm.identishield.mobile.daily.StoppedScreen
import cm.identishield.mobile.daily.Sleeper
import cm.identishield.mobile.daily.UnlockScreen
import cm.identishield.mobile.data.RetrofitAlertRepository
import cm.identishield.mobile.data.RetrofitDailyRepository
import cm.identishield.mobile.data.RetrofitEnrolmentRepository
import cm.identishield.mobile.data.SessionHolder
import cm.identishield.mobile.data.buildMobileApi
import cm.identishield.mobile.enrolment.CheckingScreen
import cm.identishield.mobile.enrolment.ConfirmKeyScreen
import cm.identishield.mobile.enrolment.DeviceIncompatibleScreen
import cm.identishield.mobile.enrolment.EnrolError
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.EnrolmentViewModel
import cm.identishield.mobile.enrolment.KeyLostScreen
import cm.identishield.mobile.enrolment.KeyRevokedScreen
import cm.identishield.mobile.enrolment.NumberScreen
import cm.identishield.mobile.enrolment.OtpScreen
import cm.identishield.mobile.enrolment.PinScreen
import cm.identishield.mobile.enrolment.ReboundScreen
import cm.identishield.mobile.enrolment.RecoveryScreen
import cm.identishield.mobile.enrolment.RecoveryWaitScreen
import cm.identishield.mobile.enrolment.RecoveryKeyScreen
import cm.identishield.mobile.enrolment.ScreenLockRequiredScreen
import cm.identishield.mobile.enrolment.SecuringScreen
import cm.identishield.mobile.enrolment.WelcomeScreen
import cm.identishield.mobile.launch.LaunchScreen
import cm.identishield.mobile.launch.LaunchState
import cm.identishield.mobile.launch.LaunchViewModel
import cm.identishield.mobile.security.AndroidKeystoreDeviceKeyManager
import cm.identishield.mobile.security.BiometricLocalUnlock
import cm.identishield.mobile.security.KeyguardScreenLock
import cm.identishield.mobile.security.LocalUnlock
import cm.identishield.mobile.security.PrefsBoundDeviceStore
import cm.identishield.mobile.security.PrefsThrottleStore
import cm.identishield.mobile.security.RecoveryThrottle
import cm.identishield.mobile.security.applyTouchProtection
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration

/** Les déverrouillages locaux, un par usage (le texte de la boîte système dit ce qui est signé). */
class Unlocks(
    val securing: LocalUnlock,
    val session: LocalUnlock,
    val transfer: LocalUnlock,
    val cancel: LocalUnlock,
    val alert: LocalUnlock,
)

/** FragmentActivity : exigée par BiometricPrompt (déverrouillage local lié à la signature). */
class MainActivity : FragmentActivity() {
    // Injection manuelle (ADR-037) : les dépendances sont construites ici et passées par constructeur.
    private val keys by lazy { AndroidKeystoreDeviceKeyManager() }
    private val boundDevice by lazy { PrefsBoundDeviceStore(applicationContext) }
    private val api by lazy { buildMobileApi(BuildConfig.API_BASE_URL) }

    // Flux D (M-08) : délai croissant local, mêmes paramètres nommés que le serveur. L'horloge système
    // n'est lue qu'ici, au point d'assemblage ; partout ailleurs, elle est injectée.
    private val throttle by lazy {
        RecoveryThrottle(
            PrefsThrottleStore(applicationContext),
            Clock.systemUTC(),
            BuildConfig.REENROL_ATTEMPTS,
            Duration.ofSeconds(BuildConfig.LOCK_BASE_SECONDS),
            Duration.ofSeconds(BuildConfig.LOCK_MAX_SECONDS),
        )
    }
    private val launchViewModel: LaunchViewModel by viewModels { LaunchViewModel.Factory(keys) }
    private val enrolmentViewModel: EnrolmentViewModel by viewModels {
        EnrolmentViewModel.Factory(RetrofitEnrolmentRepository(api), keys, boundDevice, session, throttle, KeyguardScreenLock(applicationContext))
    }
    private val dailyViewModel: DailyViewModel by viewModels {
        DailyViewModel.Factory(RetrofitDailyRepository(api), keys, boundDevice, session)
    }
    private val alertsViewModel: AlertsViewModel by viewModels {
        AlertsViewModel.Factory(
            RetrofitAlertRepository(api),
            keys,
            session,
            Duration.ofSeconds(BuildConfig.POLL_FOREGROUND_SECONDS),
            Sleeper { delay(it.toMillis()) },
        )
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
                alert = prompt(R.string.alert_prompt_title, R.string.unlock_prompt_subtitle),
            )
        setContent {
            IdentiShieldTheme {
                AppRoot(
                    launchViewModel,
                    enrolmentViewModel,
                    dailyViewModel,
                    alertsViewModel,
                    unlocks,
                    Build.MODEL,
                    boundMsisdn = { boundDevice.load()?.msisdn },
                    onQuit = ::finish,
                    onOpenSecuritySettings = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                )
            }
        }
    }

    /** Retour des réglages (D3) : la précondition « verrou d'écran » est revérifiée, la liaison reprend. */
    override fun onResume() {
        super.onResume()
        enrolmentViewModel.screenLockConfigured()
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
    alerts: AlertsViewModel,
    unlocks: Unlocks,
    deviceModel: String,
    boundMsisdn: () -> String?,
    onQuit: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val launchState by launch.state.collectAsState()
    val enrolStep by enrolment.step.collectAsState()
    val dailyStep by daily.step.collectAsState()
    if (launchState == LaunchState.Checking) {
        LaunchScreen() // E-01
        return
    }
    val enrolled = enrolStep is EnrolStep.Home // fin du flux A ou D : la session est ouverte
    LaunchedEffect(launchState, enrolled) {
        if (launchState == LaunchState.KeyPresent || enrolled) daily.start()
    }
    val inDaily = enrolled || (launchState == LaunchState.KeyPresent && dailyStep != DailyStep.NotBound)
    if (inDaily) {
        DailyRoot(daily, dailyStep, alerts, unlocks, deviceModel, boundMsisdn) { msisdn ->
            // E-27a « Sécuriser à nouveau » : flux D, numéro mémorisé ; le SERVEUR dira RECOVERY.
            enrolment.beginWithNumber(msisdn)
            daily.leaveForReenrolment()
        }
    } else {
        EnrolmentRoot(enrolment, enrolStep, unlocks, deviceModel, onQuit, onOpenSecuritySettings)
    }
}

@Composable
private fun EnrolmentRoot(
    enrolment: EnrolmentViewModel,
    step: EnrolStep,
    unlocks: Unlocks,
    deviceModel: String,
    onQuit: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val canGoBack =
        step is EnrolStep.Number || step is EnrolStep.Pin || step is EnrolStep.Otp ||
            step is EnrolStep.Recovery || step is EnrolStep.RecoveryWait || step == EnrolStep.KeyLost
    BackHandler(enabled = canGoBack) { enrolment.back() }
    when (step) {
        EnrolStep.Welcome -> WelcomeScreen(onStart = enrolment::begin)
        is EnrolStep.Number -> NumberScreen(step, enrolment::submitNumber, enrolment::back)
        is EnrolStep.Pin -> PinScreen(step, enrolment::submitPin, enrolment::back)
        is EnrolStep.Checking -> CheckingScreen()
        is EnrolStep.Otp ->
            OtpScreen(step, BuildConfig.OTP_RESEND_DELAY_SECONDS, enrolment::submitOtp, enrolment::resendOtp, enrolment::back)
        is EnrolStep.Securing ->
            if (step.error == EnrolError.DEVICE_REFUSED) {
                DeviceIncompatibleScreen(onQuit) // E-27b (M-08)
            } else {
                SecuringScreen(step, onSecure = { enrolment.secureDevice(unlocks.securing) }, onRestart = enrolment::begin)
            }
        is EnrolStep.ScreenLockRequired -> ScreenLockRequiredScreen(step, onOpenSecuritySettings, enrolment::screenLockConfigured)
        is EnrolStep.RecoveryKey -> RecoveryKeyScreen(step, enrolment::keyWrittenDown)
        is EnrolStep.Confirm -> ConfirmKeyScreen(step, BuildConfig.CODE_LENGTH, enrolment::confirmCode)
        is EnrolStep.Home -> CheckingScreen() // relais vers le flux B (AppRoot)
        is EnrolStep.Recovery ->
            RecoveryScreen(step, BuildConfig.CODE_LENGTH, enrolment::submitRecoveryCode, enrolment::lostKey, enrolment::back)
        is EnrolStep.RecoveryWait ->
            RecoveryWaitScreen(step, enrolment::waitRemaining, enrolment::recoveryWaitOver, enrolment::lostKey, enrolment::back)
        EnrolStep.KeyLost -> KeyLostScreen(onBack = enrolment::back)
        is EnrolStep.Rebound -> ReboundScreen(step, deviceModel, enrolment::showNewKey)
    }
}

@Composable
private fun DailyRoot(
    daily: DailyViewModel,
    step: DailyStep,
    alerts: AlertsViewModel,
    unlocks: Unlocks,
    deviceModel: String,
    boundMsisdn: () -> String?,
    onSecureAgain: (String) -> Unit,
) {
    val alertsScreen by alerts.screen.collectAsState()
    val alertList by alerts.alerts.collectAsState()
    val sessionLost by alerts.sessionLost.collectAsState()
    LaunchedEffect(sessionLost) {
        if (sessionLost) {
            daily.sessionLost()
            alerts.sessionLossHandled()
        }
    }
    // Flux F : interrogation à l'ouverture puis toutes les POLL_FOREGROUND, tant que l'accueil est affiché.
    if (step is DailyStep.Home) LaunchedEffect(Unit) { alerts.pollWhileForeground() }
    if (step is DailyStep.Home && alertsScreen != AlertsScreen.None) {
        val detail = alertsScreen as? AlertsScreen.Detail
        BackHandler(enabled = detail?.busy != true) { alerts.back() }
        if (detail != null) {
            AlertDetailScreen(detail, boundMsisdn(), { notMe -> alerts.answer(notMe, unlocks.alert) }, alerts::back)
        } else {
            AlertsListScreen(
                alertList,
                onOpen = alerts::open,
                onBack = alerts::back,
                onHome = alerts::close,
                onSend = {
                    alerts.close()
                    daily.startSend()
                },
                onHistory = {
                    alerts.close()
                    daily.openHistory()
                },
            )
        }
        return
    }
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
        is DailyStep.Unlock -> {
            val error = step.problem?.error
            if (error == DailyError.KEY_REVOKED || error == DailyError.KEY_INVALIDATED) {
                // E-27a (M-08) : un parcours vers le flux D, au lieu d'un simple message.
                KeyRevokedScreen(invalidated = error == DailyError.KEY_INVALIDATED) { onSecureAgain(step.msisdn) }
            } else {
                UnlockScreen(step) { pin -> daily.unlock(pin, unlocks.session) }
            }
        }
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
                pendingAlerts = alertList.filter { it.status == AlertsViewModel.STATUS_OPEN && it.actions.isNotEmpty() },
                onAlerts = alerts::openList,
                onAlert = alerts::open,
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
