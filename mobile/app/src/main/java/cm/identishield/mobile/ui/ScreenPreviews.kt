package cm.identishield.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.daily.AlertDetailScreen
import cm.identishield.mobile.daily.AlertsListScreen
import cm.identishield.mobile.daily.AlertsScreen
import cm.identishield.mobile.daily.AmountScreen
import cm.identishield.mobile.daily.AuthorizeScreen
import cm.identishield.mobile.daily.BeneficiaryScreen
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.DeferredScreen
import cm.identishield.mobile.daily.HistoryScreen
import cm.identishield.mobile.daily.Outcome
import cm.identishield.mobile.daily.ReviewScreen
import cm.identishield.mobile.daily.SentScreen
import cm.identishield.mobile.daily.StoppedScreen
import cm.identishield.mobile.daily.UnlockScreen
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.enrolment.CODE_MASK
import cm.identishield.mobile.enrolment.CheckingScreen
import cm.identishield.mobile.enrolment.ConfirmKeyScreen
import cm.identishield.mobile.enrolment.DeviceIncompatibleScreen
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.EnrolmentViewModel
import cm.identishield.mobile.enrolment.KeyLostScreen
import cm.identishield.mobile.enrolment.KeyRevokedScreen
import cm.identishield.mobile.enrolment.NumberScreen
import cm.identishield.mobile.enrolment.OtpScreen
import cm.identishield.mobile.enrolment.PinScreen
import cm.identishield.mobile.enrolment.ReboundScreen
import cm.identishield.mobile.enrolment.RecoveryKeyScreen
import cm.identishield.mobile.enrolment.RecoveryScreen
import cm.identishield.mobile.enrolment.RecoveryWaitScreen
import cm.identishield.mobile.enrolment.ScreenLockRequiredScreen
import cm.identishield.mobile.enrolment.SecuringScreen
import cm.identishield.mobile.enrolment.SecuringStage
import cm.identishield.mobile.enrolment.WelcomeScreen
import cm.identishield.mobile.launch.LaunchScreen
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Aperçus de chaque écran à 360 dp (téléphone), 600 dp et 840 dp (seuils moyen et étendu).
 * Données FICTIVES seulement : numéros 677000000–677009999, aucun PIN ; les écrans de clé (E-08, E-09,
 * E-20) ne reçoivent que des caractères de masquage, jamais un code (charte §10).
 */
@Preview(name = "360 dp", widthDp = 360, heightDp = 760, showBackground = true)
@Preview(name = "600 dp", widthDp = 600, heightDp = 960, showBackground = true)
@Preview(name = "840 dp", widthDp = 840, heightDp = 900, showBackground = true)
annotation class WidthPreviews

private val DOUALA = ZoneId.of("Africa/Douala")
private val MASK_ONLY = CODE_MASK.toString().repeat(BuildConfig.CODE_LENGTH)
private val TAIL_MASK_ONLY = CODE_MASK.toString().repeat(EnrolmentViewModel.CODE_VISIBLE_TAIL)
private val HOLDER = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = true)
private val DEFERRED = DeferredOperation("tr-7", 32500, "677000456", "2026-10-16T15:00:00Z")
private val ACCOUNT = Account("acc-000123", "677000123", "Amina NDONGO ÉLOMO", 1250000, "TRUSTED", listOf(DEFERRED))
private val TRANSACTIONS =
    listOf(
        Transaction("tr-3", "OUT", 12500, "677000456", "Jean-Paul NKOA", "EXECUTED", "2026-10-15T13:07:00Z"),
        Transaction("tr-2", "IN", 75000, "677000999", null, "EXECUTED", "2026-10-14T10:00:00Z"),
        Transaction("tr-1", "OUT", 2000, "677000111", "Marie ABENA", "FROZEN", "2026-10-12T10:00:00Z"),
    )
private val ALERT = Alert("al-1", "SIM_CHANGED", "2026-10-16T13:02:00Z", "La carte SIM de votre numéro a été changée.", listOf("ACK", "NOT_ME"), "OPEN")
private val INFO = Alert("al-2", "DEVICE_REBOUND", "2026-08-12T10:00:00Z", "Un nouveau téléphone a été lié à votre compte.", emptyList(), "INFO")

@Composable
private fun Shown(content: @Composable () -> Unit) = IdentiShieldTheme(content)

@WidthPreviews @Composable
private fun E01() = Shown { LaunchScreen() }

@WidthPreviews @Composable
private fun E02() = Shown { WelcomeScreen(onStart = {}) }

@WidthPreviews @Composable
private fun E03() = Shown { NumberScreen(EnrolStep.Number(), onSubmit = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E04() = Shown { CheckingScreen() }

@WidthPreviews @Composable
private fun E05() = Shown { OtpScreen(EnrolStep.Otp("677000200"), BuildConfig.OTP_RESEND_DELAY_SECONDS, onSubmit = {}, onResend = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E06() = Shown { PinScreen(EnrolStep.Pin("677000200"), onSubmit = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E07() = Shown { SecuringScreen(EnrolStep.Securing(SecuringStage.UNLOCKING), onSecure = {}, onRestart = {}) }

@WidthPreviews @Composable
private fun E07ScreenLock() = Shown { ScreenLockRequiredScreen(EnrolStep.ScreenLockRequired(), onOpenSettings = {}, onConfigured = {}) }

@WidthPreviews @Composable
private fun E08() = Shown { RecoveryKeyScreen(EnrolStep.RecoveryKey(MASK_ONLY, lastDisplay = false), onWritten = {}) }

@WidthPreviews @Composable
private fun E09() = Shown { ConfirmKeyScreen(EnrolStep.Confirm(TAIL_MASK_ONLY), BuildConfig.CODE_LENGTH, onConfirm = {}) }

@WidthPreviews @Composable
private fun E10() = Shown { UnlockScreen(DailyStep.Unlock("677000123")) {} }

@WidthPreviews @Composable
private fun E11() =
    Shown {
        DailyHomeScreen(
            ACCOUNT,
            "Samsung A15",
            TRANSACTIONS,
            onSend = {},
            onHistory = {},
            onDeferred = {},
            zone = DOUALA,
            pendingAlerts = listOf(ALERT),
            onAlerts = {},
            onAlert = {},
        )
    }

@WidthPreviews @Composable
private fun E12() = Shown { BeneficiaryScreen(DailyStep.Beneficiary(TRANSACTIONS.take(1)), onSubmit = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E13() = Shown { AmountScreen(DailyStep.Amount(HOLDER, 1250000, draft = "32500"), BuildConfig.QUICK_AMOUNTS_XAF.toList(), onSubmit = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E14() = Shown { ReviewScreen(DailyStep.Confirm(HOLDER, 32500), onConfirm = {}, onCancel = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E15() = Shown { AuthorizeScreen(DailyStep.Authorize(HOLDER, 32500), onSubmit = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E16() = Shown { SentScreen(DailyStep.Sent(HOLDER, 32500, "tr-42"), "Samsung A15", onHome = {}) }

@WidthPreviews @Composable
private fun E17() =
    Shown {
        DeferredScreen(DailyStep.Deferred("tr-42", 32500, "677000456", "Jean-Paul NKOA", "2026-10-16T16:00:00Z"), onCancel = {}, onHome = {}, zone = DOUALA)
    }

@WidthPreviews @Composable
private fun E18() = Shown { StoppedScreen(DailyStep.Stopped(Outcome.FROZEN), onHome = {}, onHistory = {}) }

@WidthPreviews @Composable
private fun E19() = Shown { HistoryScreen(DailyStep.History(TRANSACTIONS), onBack = {}, onHome = {}, onSend = {}, zone = DOUALA) }

@WidthPreviews @Composable
private fun E20() = Shown { RecoveryScreen(EnrolStep.Recovery(), BuildConfig.CODE_LENGTH, onSubmit = {}, onLostKey = {}, onBack = {}) }

@WidthPreviews @Composable
private fun E21() =
    Shown {
        RecoveryWaitScreen(EnrolStep.RecoveryWait(Instant.EPOCH), remaining = { Duration.ofMinutes(4) }, onWaitOver = {}, onLostKey = {}, onBack = {})
    }

@WidthPreviews @Composable
private fun E22() = Shown { ReboundScreen(EnrolStep.Rebound("677000123"), "Samsung A15", onShowKey = {}) }

@WidthPreviews @Composable
private fun E23() = Shown { AlertsListScreen(listOf(ALERT, INFO), onOpen = {}, onBack = {}, onHome = {}, onSend = {}, onHistory = {}, zone = DOUALA) }

@WidthPreviews @Composable
private fun E24() = Shown { AlertDetailScreen(AlertsScreen.Detail(ALERT), "677000123", onAnswer = {}, onBack = {}, zone = DOUALA) }

@WidthPreviews @Composable
private fun E26() = Shown { KeyLostScreen(onBack = {}) }

@WidthPreviews @Composable
private fun E27a() = Shown { KeyRevokedScreen(invalidated = false, onSecureAgain = {}) }

@WidthPreviews @Composable
private fun E27b() = Shown { DeviceIncompatibleScreen(onQuit = {}) }
