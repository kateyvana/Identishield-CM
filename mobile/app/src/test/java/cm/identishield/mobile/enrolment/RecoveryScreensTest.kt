package cm.identishield.mobile.enrolment

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** Écrans du flux D et E-27 sur la JVM (Robolectric), à la taille des maquettes. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class RecoveryScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val codeLength = BuildConfig.CODE_LENGTH
    private val code = "K7PQ3MZX9DHTW2RC8NBVQ4TN".take(codeLength)

    private fun secureFlagSet() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { IdentiShieldTheme { content() } }

    @Test
    fun e20_isCaptureProtected_normalisesTheCode_andSubmitsOnlyWhenComplete() {
        var submitted: String? = null
        var lost = false
        show { RecoveryScreen(EnrolStep.Recovery(EnrolError.RECOVERY_INVALID), codeLength, onSubmit = { submitted = it }, onLostKey = { lost = true }, onBack = {}) }
        assertTrue("MOB-S-05 : capture bloquée sur E-20", secureFlagSet())
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        compose.onNodeWithText("Ce compte est déjà protégé par un autre téléphone.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Clé incorrecte").assertIsDisplayed()
        compose.onNodeWithText("Aucun code ne vous sera envoyé par SMS pour cette étape.").assertIsDisplayed()
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        compose.onNodeWithTag("recovery-input").performTextInput(code.lowercase().dropLast(1))
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        compose.onNodeWithTag("recovery-input").performTextInput(code.last().lowercase().toString())
        compose.onNodeWithTag("primary").assertIsEnabled().performClick()
        assertEquals("majuscules, sans séparateur", code, submitted)
        compose.onNodeWithTag("lost-key").performClick()
        assertTrue(lost)
    }

    @Test
    fun codeTransformation_groupsByThree_masksAllButTheTail_andMapsOffsetsBothWays() {
        val plain = CodeTransformation(visibleTail = null).filter(AnnotatedString("K7PQ3MZX9"))
        assertEquals("K7P-Q3M-ZX9", plain.text.text)
        assertEquals(3, plain.offsetMapping.originalToTransformed(3))
        assertEquals(5, plain.offsetMapping.originalToTransformed(4))
        assertEquals(11, plain.offsetMapping.originalToTransformed(9))
        assertEquals(3, plain.offsetMapping.transformedToOriginal(4))
        assertEquals(9, plain.offsetMapping.transformedToOriginal(11))
        val masked = CodeTransformation(visibleTail = 2).filter(AnnotatedString("K7PQ3M"))
        assertEquals("•••-•3M", masked.text.text)
        assertEquals("K7P-Q3M", maskedCode("K7PQ3M", null))
    }

    @Test
    fun formatWait_minutesSeconds_hours_andCeiling() {
        assertEquals("04:00", formatWait(Duration.ofMinutes(4)))
        assertEquals("1:01:01", formatWait(Duration.ofSeconds(3661)))
        assertEquals("00:02", formatWait(Duration.ofMillis(1500)))
    }

    @Test
    fun e21_isCaptureProtected_countsDownOnTheInjectedClock_andReturnsWhenOver() {
        var remaining: Duration? = Duration.ofMinutes(4)
        var over = false
        compose.mainClock.autoAdvance = false
        show {
            RecoveryWaitScreen(
                EnrolStep.RecoveryWait(Instant.parse("2026-10-08T10:04:00Z")),
                remaining = { remaining },
                onWaitOver = { over = true },
                onLostKey = {},
                onBack = {},
            )
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("MOB-S-05 : capture bloquée sur E-21", secureFlagSet())
        compose.onNodeWithText("Clé incorrecte").assertIsDisplayed()
        compose.onNodeWithText("04:00").assertIsDisplayed()
        compose.onNodeWithTag("not-blocked").assertIsDisplayed()
        compose.onNodeWithText("Votre compte n'est pas bloqué.", substring = true).assertIsDisplayed()
        remaining = Duration.ofSeconds(59)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("00:59").assertIsDisplayed()
        assertFalse(over)
        remaining = null
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("l'attente écoulée ramène à E-20", over)
    }

    @Test
    fun e22_showsOldPhoneUnbound_thisPhoneBound_andOpensTheNewKey() {
        var shown = false
        show { ReboundScreen(EnrolStep.Rebound("677000123"), "Samsung A15", onShowKey = { shown = true }) }
        compose.onNodeWithText("Ce téléphone est maintenant votre clé").assertIsDisplayed()
        compose.onNodeWithText("677 000 123").assertIsDisplayed()
        compose.onNodeWithText("délié").assertIsDisplayed()
        compose.onNodeWithText("lié").assertIsDisplayed()
        compose.onNodeWithText("Ce téléphone (Samsung A15)").assertIsDisplayed()
        compose.onNodeWithText("une seule fois", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("show-key").performClick()
        assertTrue(shown)
        assertFalse("E-22 n'est pas un écran sensible", secureFlagSet())
    }

    @Test
    fun e26_orientationOnly_withTheOpenPoint() {
        var back = 0
        show { KeyLostScreen(onBack = { back++ }) }
        compose.onNodeWithText("Vous avez perdu votre clé ?").assertIsDisplayed()
        compose.onNodeWithText("1. Rendez-vous chez un agent MTN").assertIsDisplayed()
        compose.onNodeWithText("QM-05", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("lost-back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun e27a_revokedOrInvalidated_leadsToReenrolment() {
        var secure = 0
        var invalidated by mutableStateOf(false)
        show { KeyRevokedScreen(invalidated = invalidated, onSecureAgain = { secure++ }) }
        compose.onNodeWithText("Cet appareil n'est plus la clé de votre compte").assertIsDisplayed()
        compose.onNodeWithTag("revoked-warning").assertIsDisplayed()
        compose.onNodeWithText("Continuer par USSD").assertIsDisplayed()
        compose.onNodeWithTag("secure-again").performClick()
        assertEquals(1, secure)
        invalidated = true
        compose.onNodeWithText("La biométrie de votre téléphone a changé").assertIsDisplayed()
        compose.onNodeWithTag("revoked-warning").assertDoesNotExist()
        compose.onNodeWithTag("secure-again").assertIsDisplayed()
    }

    @Test
    fun e27b_incompatibleDevice_quits() {
        var quit = false
        show { DeviceIncompatibleScreen(onQuit = { quit = true }) }
        compose.onNodeWithText("Ce téléphone ne peut pas protéger votre compte").assertIsDisplayed()
        compose.onNodeWithText("Android 11 minimum", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("quit").performClick()
        assertTrue(quit)
    }
}
