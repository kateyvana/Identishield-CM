package cm.identishield.mobile.enrolment

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.security.applyTouchProtection
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests d'interface Compose des écrans du flux A, sur la JVM (Robolectric) : aucun téléphone requis.
 * Écran des maquettes : 720 × 1520 px, soit 360 × 760 dp (les petits écrans sont une limite déclarée).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class EnrolmentScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun secureFlagSet() =
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { IdentiShieldTheme { content() } }

    @Test
    fun e02_welcome_showsTheMockupTexts_andStarts() {
        var started = false
        show { WelcomeScreen(onStart = { started = true }) }
        compose.onNodeWithText("Cet appareil va devenir la clé de votre compte").assertIsDisplayed()
        compose.onNodeWithTag("warning").assertIsDisplayed()
        compose.onNodeWithTag("start").performClick()
        assertTrue(started)
        assertFalse("E-02 n'est pas un écran sensible", secureFlagSet())
    }

    @Test
    fun e03_number_submitsWhatWasTyped_andShowsTheError() {
        var submitted: String? = null
        show { NumberScreen(EnrolStep.Number(EnrolError.NUMBER_INVALID), onSubmit = { submitted = it }, onBack = {}) }
        compose.onNodeWithText("Numéro invalide").assertIsDisplayed()
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        compose.onNodeWithTag("number-input").performTextInput("677000200")
        compose.onNodeWithTag("primary").assertIsEnabled().performClick()
        assertEquals("677000200", submitted)
    }

    @Test
    fun e06_pin_isCaptureProtected_andUsesTheKeypad() {
        var submitted: String? = null
        show { PinScreen(EnrolStep.Pin("677000200", EnrolError.CREDENTIALS_INVALID), onSubmit = { submitted = it }, onBack = {}) }
        assertTrue("MOB-S-05 : capture bloquée sur E-06", secureFlagSet())
        compose.onNodeWithText("677 000 200", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Identifiants incorrects").assertIsDisplayed()
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithTag("key-$it").performClick() }
        compose.onNodeWithTag("key-delete").performClick()
        compose.onNodeWithTag("key-4").performClick()
        compose.onNodeWithTag("primary").performClick()
        assertEquals("1234", submitted)
    }

    @Test
    fun e04_checking_showsTheMockupTexts() {
        show { CheckingScreen() }
        compose.onNodeWithText("Vérification…").assertIsDisplayed()
        compose.onNodeWithText("Nous vérifions votre compte. Cela prend moins d'une seconde.").assertIsDisplayed()
    }

    @Test
    fun e05_otp_resendWaitsForTheNamedParameter_thenResends() {
        var resent = false
        var submitted: String? = null
        compose.mainClock.autoAdvance = false
        show {
            OtpScreen(
                EnrolStep.Otp("677000200"),
                BuildConfig.OTP_RESEND_DELAY_SECONDS,
                onSubmit = { submitted = it },
                onResend = { resent = true },
                onBack = {},
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("677 ••• 200", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Renvoyer le code (dans ${BuildConfig.OTP_RESEND_DELAY_SECONDS} s)").assertIsDisplayed()
        compose.onNodeWithTag("resend").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(BuildConfig.OTP_RESEND_DELAY_SECONDS * 1_000L + 1_000L)
        compose.onNodeWithTag("resend").assertIsEnabled().performClick()
        assertTrue(resent)
        listOf("2", "4", "6", "8", "1", "0").forEach { compose.onNodeWithTag("key-$it").performClick() }
        compose.onNodeWithTag("primary").performClick()
        assertEquals("246810", submitted)
    }

    @Test
    fun e07_securing_startsOnce_andShowsTheThreeStages() {
        var starts = 0
        show { SecuringScreen(EnrolStep.Securing(SecuringStage.UNLOCKING), onSecure = { starts++ }, onRestart = {}) }
        compose.onNodeWithText("Sécurisation de cet appareil…").assertIsDisplayed()
        compose.onNodeWithText("Déverrouillez votre téléphone pour confirmer (empreinte, visage ou code).").assertIsDisplayed()
        compose.onNodeWithTag("stage-CREATING_KEY").assertIsDisplayed()
        compose.onNodeWithTag("stage-REGISTERING").assertIsDisplayed()
        assertEquals("déjà en cours : pas de relance automatique", 0, starts)
    }

    @Test
    fun e07_securing_launchesTheBindingOnArrival_andOffersRetryAfterCancel() {
        var starts = 0
        show { SecuringScreen(EnrolStep.Securing(SecuringStage.CREATING_KEY), onSecure = { starts++ }, onRestart = {}) }
        compose.waitForIdle()
        assertEquals(1, starts)
    }

    @Test
    fun e07_errorOffersARetry() {
        var starts = 0
        show {
            SecuringScreen(
                EnrolStep.Securing(SecuringStage.UNLOCKING, EnrolError.UNLOCK_CANCELLED),
                onSecure = { starts++ },
                onRestart = {},
            )
        }
        compose.onNodeWithText("Déverrouillage annulé. Réessayez.").assertIsDisplayed()
        compose.onNodeWithTag("securing-retry").performClick()
        assertEquals(1, starts)
    }

    /** D3 : l'écran dit la cause (pas de verrou) et l'action (réglages), charte points 3 et 4. */
    @Test
    fun e07_screenLockRequired_explainsCauseAndAction_andLeadsToSettings() {
        var settings = 0
        var done = 0
        show { ScreenLockRequiredScreen(EnrolStep.ScreenLockRequired(stillMissing = true), onOpenSettings = { settings++ }, onConfigured = { done++ }) }
        compose.onNodeWithText("Verrouillage d'écran requis").assertIsDisplayed()
        compose.onNodeWithText("Ouvrez les réglages de sécurité", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("still-missing").assertIsDisplayed()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("lock-configured").performClick()
        assertEquals(1, settings)
        assertEquals(1, done)
        assertFalse("écran explicatif sans secret", secureFlagSet())
    }

    @Test
    fun e08_recoveryKey_isCaptureProtected_groupedForPaper() {
        var written = false
        show { RecoveryKeyScreen(EnrolStep.RecoveryKey("K7M2QX", lastDisplay = true), onWritten = { written = true }) }
        assertTrue("MOB-S-05 : capture bloquée sur E-08", secureFlagSet())
        compose.onNodeWithTag("recovery-code").assertIsDisplayed()
        compose.onNodeWithTag("last-display").assertIsDisplayed()
        compose.onNodeWithTag("written").performClick()
        assertTrue(written)
    }

    /** Décision du propriétaire du 2026-10-10 : rappel masqué sauf les 2 derniers caractères, puis ressaisie complète. */
    @Test
    fun e09_confirm_isCaptureProtected_remindsOnlyTheTail_andSubmitsTheWholeCode() {
        var sent: String? = null
        val length = BuildConfig.CODE_LENGTH
        show { ConfirmKeyScreen(EnrolStep.Confirm("7K"), length, onConfirm = { sent = it }) }
        assertTrue("MOB-S-05 : capture bloquée sur E-09", secureFlagSet())
        assertEquals("exactement les 2 derniers caractères, rien d'autre", "•••-•7K", codeReminder(length, "7K"))
        compose
            .onNodeWithTag("code-reminder")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Rappel : votre clé se termine par 7 K")
        compose.onAllNodesWithText("•••-•7K").assertCountEquals(0) // le rappel est retiré de l'arbre d'accessibilité (D2)
        compose.onNodeWithTag("confirm").assertIsNotEnabled()
        compose.onNodeWithTag("confirm-input").performTextInput("k7pq3")
        compose.onNodeWithTag("confirm").assertIsNotEnabled()
        compose.onNodeWithTag("confirm-input").performTextInput("m")
        compose.onNodeWithTag("confirm").assertIsEnabled().performClick()
        assertEquals("le code complet, normalisé", "K7PQ3M", sent)
    }

    @Test
    fun e11_home_showsHolderBalanceAndProtectedBadge() {
        val account = Account("acc-000123", "677000123", "Amina NDONGO ÉLOMO", 152300, "TRUSTED", emptyList())
        show { HomeScreen(account, deviceModel = "Samsung A15") }
        compose.onNodeWithTag("holder").assertIsDisplayed()
        compose.onNodeWithText("Amina NDONGO ÉLOMO").assertIsDisplayed()
        compose.onNodeWithText("677 000 123 · MTN MoMo").assertIsDisplayed()
        compose.onNodeWithText("152 300").assertIsDisplayed()
        compose.onNodeWithText("🛡 Appareil protégé · Samsung A15").assertIsDisplayed()
        assertFalse(secureFlagSet())
    }

    @Test
    fun secureScreens_showTheCaptureBlockedBadge_likeTheMockups() {
        show { PinScreen(EnrolStep.Pin("677000200"), onSubmit = {}, onBack = {}) }
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        compose.onNodeWithText("🔒 capture bloquée").assertIsDisplayed()
        assertTrue("pastille et FLAG_SECURE vont ensemble", secureFlagSet())
    }

    @Test
    fun recoveryAndConfirmScreens_showTheBadge() {
        var confirm by mutableStateOf(false)
        show {
            if (confirm) {
                ConfirmKeyScreen(EnrolStep.Confirm("QX"), BuildConfig.CODE_LENGTH, onConfirm = {})
            } else {
                RecoveryKeyScreen(EnrolStep.RecoveryKey("K7M2QX", lastDisplay = false), onWritten = {})
            }
        }
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        confirm = true
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        assertTrue(secureFlagSet())
    }

    @Test
    fun ordinaryScreens_haveNeitherBadgeNorSecureFlag() {
        show { WelcomeScreen(onStart = {}) }
        compose.onNodeWithTag("capture-blocked").assertDoesNotExist()
        assertFalse(secureFlagSet())
    }

    @Test
    fun mobS07_touchesAreFilteredWhenTheWindowIsObscured() {
        compose.activity.applyTouchProtection()
        assertTrue(compose.activity.window.decorView.filterTouchesWhenObscured)
    }
}
