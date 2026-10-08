package cm.identishield.mobile.daily

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.Alert
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

private val DOUALA = ZoneId.of("Africa/Douala")
private val SIM = Alert("al-1", "SIM_CHANGED", "2026-10-08T13:02:00Z", "La carte SIM de votre numéro a été changée.", listOf("ACK", "NOT_ME"), "OPEN")
private val INFO = Alert("al-2", "DEVICE_REBOUND", "2026-08-12T10:00:00Z", "Un nouveau téléphone a été lié à votre compte.", emptyList(), "INFO")
private val ACCOUNT = Account("acc-000123", "677000123", "Amina NDONGO ÉLOMO", 152300, "TRUSTED", emptyList())

/** Écrans du flux F (E-11 bandeau, E-23, E-24) sur la JVM. Une alerte n'affiche jamais de lien ni de code. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class AlertScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { IdentiShieldTheme { content() } }

    @Test
    fun e11_bannerAndCounter_forThePendingAlert_only() {
        var opened: Alert? = null
        var list = false
        var pending by mutableStateOf(listOf(SIM))
        show { DailyHomeScreen(ACCOUNT, "Samsung A15", pendingAlerts = pending, onAlerts = { list = true }, onAlert = { opened = it }, zone = DOUALA) }
        compose.onNodeWithTag("alert-banner").assertIsDisplayed()
        compose.onNodeWithText("La carte SIM de votre numéro a été changée.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Alertes 1").assertIsDisplayed()
        compose.onNodeWithTag("alert-banner").performClick()
        assertEquals(SIM, opened)
        compose.onNodeWithTag("tile-alerts").performClick()
        assertTrue(list)
        pending = emptyList()
        compose.onNodeWithTag("alert-banner").assertDoesNotExist()
        compose.onNodeWithText("Alertes 1").assertDoesNotExist() // plus de compteur sur la tuile
        compose.onNodeWithTag("tile-alerts").assertIsDisplayed()
    }

    @Test
    fun e23_list_marksWhatIsToHandle_andOpensAnAlert() {
        var opened: Alert? = null
        show { AlertsListScreen(listOf(SIM, INFO), onOpen = { opened = it }, onBack = {}, onHome = {}, onSend = {}, onHistory = {}, zone = DOUALA) }
        compose.onNodeWithText("Alertes de sécurité").assertIsDisplayed()
        compose.onNodeWithText("Carte SIM changée").assertIsDisplayed()
        // Le badge est un enfant d'une carte cliquable (nœud fusionné) : on lit l'arbre non fusionné.
        compose.onNodeWithTag("to-handle-al-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Nouveau téléphone lié").assertIsDisplayed()
        compose.onNodeWithTag("to-handle-al-2", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("8 oct. à 14:02", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("alert-al-2").performClick()
        assertEquals(INFO, opened)
        compose.onNodeWithTag("bottom-nav").assertIsDisplayed()
    }

    @Test
    fun e23_empty() {
        show { AlertsListScreen(emptyList(), onOpen = {}, onBack = {}, onHome = {}, onSend = {}, onHistory = {}) }
        compose.onNodeWithText("Aucune alerte.").assertIsDisplayed()
    }

    @Test
    fun e24_openAlert_offersTheTwoSignedAnswers_andNeverAsksForACode() {
        val answers = mutableListOf<Boolean>()
        var state by mutableStateOf(AlertsScreen.Detail(SIM))
        show { AlertDetailScreen(state, "677000123", onAnswer = { answers += it }, onBack = {}, zone = DOUALA) }
        compose.onNodeWithText("Carte SIM changée").assertIsDisplayed()
        compose.onNodeWithTag("alert-summary").assertIsDisplayed()
        compose.onNodeWithText("677 000 123").assertIsDisplayed()
        compose.onNodeWithText("toujours lié").assertIsDisplayed()
        compose.onNodeWithText("C'est vous ?").assertIsDisplayed()
        compose.onNodeWithText("Nous ne vous demanderons jamais de code ni de PIN dans une alerte.").assertIsDisplayed()
        compose.onNodeWithTag("answer-not-me").performClick()
        compose.onNodeWithTag("answer-ack").performClick()
        assertEquals(listOf(true, false), answers)
        state = state.copy(busy = true)
        compose.onNodeWithTag("answer-ack").performClick()
        assertEquals("occupé : rien de plus", 2, answers.size)
        compose.onNodeWithTag("back").assertDoesNotExist()
        state = AlertsScreen.Detail(SIM.copy(status = "NOT_ME", actions = emptyList()), accountState = "FROZEN")
        compose.onNodeWithTag("answered").assertIsDisplayed()
        compose.onNodeWithText("gelé pour votre sécurité", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("answer-ack").assertDoesNotExist()
    }

    @Test
    fun e24_informativeAlert_hasNoButtons() {
        show { AlertDetailScreen(AlertsScreen.Detail(INFO), null, onAnswer = {}, onBack = {}, zone = DOUALA) }
        compose.onNodeWithText("Nouveau téléphone lié").assertIsDisplayed()
        compose.onNodeWithTag("answer-ack").assertDoesNotExist()
        compose.onNodeWithTag("answer-not-me").assertDoesNotExist()
        compose.onNodeWithText("C'est vous ?").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)
    }
}
