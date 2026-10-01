package cm.identishield.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import cm.identishield.mobile.data.RetrofitEnrolmentRepository
import cm.identishield.mobile.data.buildMobileApi
import cm.identishield.mobile.enrolment.CheckingScreen
import cm.identishield.mobile.enrolment.ConfirmKeyScreen
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.EnrolmentViewModel
import cm.identishield.mobile.enrolment.HomeScreen
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
import cm.identishield.mobile.security.applyTouchProtection
import cm.identishield.mobile.ui.theme.IdentiShieldTheme

/** FragmentActivity : exigée par BiometricPrompt (déverrouillage local lié à la signature). */
class MainActivity : FragmentActivity() {
    // Injection manuelle (ADR-037) : les dépendances sont construites ici et passées par constructeur.
    private val keys by lazy { AndroidKeystoreDeviceKeyManager() }
    private val launchViewModel: LaunchViewModel by viewModels { LaunchViewModel.Factory(keys) }
    private val enrolmentViewModel: EnrolmentViewModel by viewModels {
        EnrolmentViewModel.Factory(RetrofitEnrolmentRepository(buildMobileApi(BuildConfig.API_BASE_URL)), keys)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyTouchProtection() // MOB-S-07
        launchViewModel.check()
        val unlock =
            BiometricLocalUnlock(
                this,
                getString(R.string.securing_prompt_title),
                getString(R.string.securing_prompt_subtitle),
            )
        setContent {
            IdentiShieldTheme {
                AppRoot(launchViewModel, enrolmentViewModel, unlock, Build.MODEL)
            }
        }
    }
}

@Composable
fun AppRoot(
    launch: LaunchViewModel,
    enrolment: EnrolmentViewModel,
    unlock: LocalUnlock,
    deviceModel: String,
) {
    val launchState by launch.state.collectAsState()
    if (launchState != LaunchState.NoKey) {
        LaunchScreen() // E-01 ; clé présente : déverrouillage E-10 (carte M-07)
        return
    }
    val step by enrolment.step.collectAsState()
    BackHandler(enabled = step is EnrolStep.Number || step is EnrolStep.Pin || step is EnrolStep.Otp) {
        enrolment.back()
    }
    when (val s = step) {
        EnrolStep.Welcome -> WelcomeScreen(onStart = enrolment::begin)
        is EnrolStep.Number -> NumberScreen(s, enrolment::submitNumber, enrolment::back)
        is EnrolStep.Pin -> PinScreen(s, enrolment::submitPin, enrolment::back)
        is EnrolStep.Checking -> CheckingScreen()
        is EnrolStep.Otp ->
            OtpScreen(s, BuildConfig.OTP_RESEND_DELAY_SECONDS, enrolment::submitOtp, enrolment::resendOtp, enrolment::back)
        is EnrolStep.Securing -> SecuringScreen(s, onSecure = { enrolment.secureDevice(unlock) }, onRestart = enrolment::begin)
        is EnrolStep.RecoveryKey -> RecoveryKeyScreen(s, enrolment::keyWrittenDown)
        is EnrolStep.Confirm -> ConfirmKeyScreen(s, enrolment::confirmCharacters)
        is EnrolStep.Home -> HomeScreen(s.account, deviceModel)
    }
}
