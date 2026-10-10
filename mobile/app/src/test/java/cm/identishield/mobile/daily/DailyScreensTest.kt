package cm.identishield.mobile.daily

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.DeferredOperation
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.data.Transaction
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

private val DOUALA = ZoneId.of("Africa/Douala") // UTC+1, sans heure d'été : rendu déterministe
private val JEAN = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = true)
private val ACCOUNT = Account("acc-000123", "677000123", "Amina NDONGO ÉLOMO", 152300, "TRUSTED", emptyList())
private val TXS =
    listOf(
        Transaction("tr-3", "OUT", 25000, "677000456", "Jean-Paul NKOA", "EXECUTED", "2026-09-25T13:07:00Z"),
        Transaction("tr-2", "IN", 100000, "677000999", null, "EXECUTED", "2026-09-23T10:00:00Z"),
        Transaction("tr-1", "OUT", 1000, "677000111", "Marie ABENA", "FROZEN", "2026-09-20T10:00:00Z"),
    )

/** Tests d'interface des flux B et C sur la JVM (Robolectric), à la taille des maquettes. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class DailyScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun secureFlagSet() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { IdentiShieldTheme { content() } }

    @Test
    fun e10_unlock_showsTheMaskedNumber_neverTheName_andSubmitsThePin() {
        var pin: String? = null
        show { UnlockScreen(DailyStep.Unlock("677000123")) { pin = it } }
        compose.onNodeWithTag("masked-number").assertIsDisplayed()
        compose.onNodeWithText("677 ••• 123").assertIsDisplayed()
        compose.onNodeWithText("Amina", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Appareil protégé", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithTag("key-$it").performClick() }
        compose.onNodeWithTag("primary").performClick()
        assertEquals("1234", pin)
    }

    @Test
    fun e10_errors_uniformCredentials_andThrottleWithoutBlocking() {
        var step by androidx.compose.runtime.mutableStateOf(DailyStep.Unlock("677000123", Problem(DailyError.CREDENTIALS_INVALID)))
        show { UnlockScreen(step) {} }
        compose.onNodeWithText("Identifiants incorrects").assertIsDisplayed()
        step = DailyStep.Unlock("677000123", Problem(DailyError.THROTTLED, 90))
        compose.onNodeWithText("Trop d'essais. Réessayez dans 2 min.").assertIsDisplayed()
        compose.onNodeWithText("bloqué", substring = true).assertDoesNotExist()
        step = DailyStep.Unlock("677000123", Problem(DailyError.KEY_REVOKED))
        compose.onNodeWithText("Cet appareil n'est plus la clé de votre compte.").assertIsDisplayed()
    }

    @Test
    fun e11_home_balanceRecentOperationsDeferredAndNavigation() {
        val op = DeferredOperation("tr-7", 30000, "677000456", "2026-10-01T15:00:00Z")
        var sent = false
        var history = false
        var deferred: DeferredOperation? = null
        show {
            DailyHomeScreen(
                ACCOUNT.copy(deferredOperations = listOf(op)),
                "Samsung A15",
                TXS,
                onSend = { sent = true },
                onHistory = { history = true },
                onDeferred = { deferred = it },
                zone = DOUALA,
            )
        }
        compose.onNodeWithText("152 300").assertIsDisplayed()
        compose.onNodeWithText("🛡 Appareil protégé · Samsung A15").assertIsDisplayed()
        compose.onNodeWithText("Dernières opérations").performScrollTo().assertIsDisplayed() // la zone défile
        compose.onNodeWithTag("tx-tr-3").assertExists()
        compose.onNodeWithTag("tx-tr-1").assertDoesNotExist() // deux lignes, comme la maquette
        compose.onNodeWithText("Alerte de sécurité", substring = true).assertDoesNotExist() // pas de bandeau (M-08)
        compose.onNodeWithText("30 000 FCFA → 677 000 456").assertExists()
        compose.onNodeWithText("1 oct. à 16:00").assertExists()
        compose.onNodeWithTag("deferred-tr-7").performClick()
        assertEquals(op, deferred)
        compose.onNodeWithTag("tile-send").performClick()
        compose.onNodeWithTag("nav-history").performClick()
        assertTrue(sent && history)
        compose.onNodeWithTag("bottom-nav").assertIsDisplayed()
    }

    @Test
    fun e11_likeTheMockup_recentOperationsVisibleWithoutScrolling() {
        show { DailyHomeScreen(ACCOUNT, "Samsung A15", TXS, onSend = {}, onHistory = {}, zone = DOUALA) }
        compose.onNodeWithText("Dernières opérations").assertIsDisplayed()
        compose.onNodeWithTag("tx-tr-3").assertIsDisplayed()
        compose.onNodeWithTag("tile-alerts").assertIsDisplayed() // grisée : M-08
    }

    @Test
    fun e19_history_onlyContractFields_withStatusBadges() {
        show { HistoryScreen(DailyStep.History(TXS), onBack = {}, onHome = {}, onSend = {}, zone = DOUALA) }
        compose.onNodeWithText("Jean-Paul NKOA").assertIsDisplayed()
        compose.onNodeWithText("25 sept. 14:07 · Envoi").assertIsDisplayed()
        compose.onNodeWithText("- 25 000").assertIsDisplayed()
        compose.onNodeWithText("677 000 999").assertIsDisplayed() // nom absent du contrat : le numéro
        compose.onNodeWithText("+ 100 000").assertIsDisplayed()
        compose.onNodeWithTag("status-tr-1").assertIsDisplayed()
        compose.onNodeWithText("signé", substring = true).assertDoesNotExist()
    }

    @Test
    fun e12_beneficiary_recentsAreKnownPayees_andInputSubmits() {
        val submitted = mutableListOf<String>()
        show { BeneficiaryScreen(DailyStep.Beneficiary(listOf(TXS[0])), onSubmit = { submitted += it }, onBack = {}) }
        compose.onNodeWithText("connu").assertIsDisplayed()
        compose.onNodeWithTag("recent-677000456").performClick()
        compose.onNodeWithTag("beneficiary-input").performTextInput("677000789")
        compose.onNodeWithTag("primary").performClick()
        assertEquals(listOf("677000456", "677000789"), submitted)
    }

    @Test
    fun e13_amount_quickAmountsComeFromTheRuleset_andShowTheBalanceAfter() {
        var amount: Long? = null
        val quick = BuildConfig.QUICK_AMOUNTS_XAF.toList()
        show { AmountScreen(DailyStep.Amount(JEAN, 152300), quick, onSubmit = { amount = it }, onBack = {}) }
        compose.onNodeWithText("Jean-Paul NKOA", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        quick.forEach { compose.onNodeWithTag("quick-$it").assertIsDisplayed() }
        compose.onNodeWithTag("quick-${quick[2]}").performClick()
        compose.onNodeWithText("Solde après envoi : ${formatXafForTest(152300 - quick[2])} FCFA").assertIsDisplayed()
        compose.onNodeWithTag("primary").assertIsEnabled().performClick()
        assertEquals(quick[2], amount)
    }

    // @covers ED-11
    @Test
    fun e14_review_isCaptureProtected_holderNameNewBadge_noFees() {
        var confirmed = false
        show { ReviewScreen(DailyStep.Confirm(JEAN, 25000), onConfirm = { confirmed = true }, onCancel = {}, onBack = {}) }
        assertTrue("MOB-S-05 : capture bloquée sur E-14", secureFlagSet())
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        compose.onNodeWithTag("holder-name").assertIsDisplayed()
        compose.onNodeWithTag("new-beneficiary").assertIsDisplayed()
        compose.onNodeWithTag("new-warning").assertIsDisplayed()
        compose.onNodeWithText("25 000 FCFA").assertIsDisplayed()
        compose.onNodeWithText("Frais", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun e14_knownBeneficiary_hasNoNewBadge() {
        show { ReviewScreen(DailyStep.Confirm(JEAN.copy(newBeneficiary = false), 25000), onConfirm = {}, onCancel = {}, onBack = {}) }
        compose.onNodeWithTag("new-beneficiary").assertDoesNotExist()
        compose.onNodeWithTag("new-warning").assertDoesNotExist()
    }

    @Test
    fun e15_authorize_isCaptureProtected_pinThenLocalUnlock() {
        var pin: String? = null
        show { AuthorizeScreen(DailyStep.Authorize(JEAN, 25000, Problem(DailyError.CREDENTIALS_INVALID)), { pin = it }, onBack = {}) }
        assertTrue("MOB-S-05 : capture bloquée sur E-15", secureFlagSet())
        compose.onNodeWithTag("capture-blocked").assertIsDisplayed()
        compose.onNodeWithText("25 000 FCFA → Jean-Paul NKOA").assertIsDisplayed()
        compose.onNodeWithText("Code PIN incorrect").assertIsDisplayed()
        compose.onNodeWithTag("unlock-card").assertIsDisplayed()
        listOf("4", "3", "2", "1").forEach { compose.onNodeWithTag("key-$it").performClick() }
        compose.onNodeWithTag("primary").performClick()
        assertEquals("4321", pin)
    }

    @Test
    fun e15_busy_blocksInputAndSubmit() {
        show { AuthorizeScreen(DailyStep.Authorize(JEAN, 25000, busy = true), {}, onBack = {}) }
        compose.onNodeWithTag("key-1").performClick()
        compose.onNodeWithTag("primary").assertIsNotEnabled()
        compose.onNodeWithTag("back").assertDoesNotExist()
    }

    @Test
    fun e16_sent_receiptWithServerReference_noDate() {
        show { SentScreen(DailyStep.Sent(JEAN, 25000, "tr-42"), "Samsung A15", onHome = {}) }
        compose.onNodeWithText("Envoyé").assertIsDisplayed()
        compose.onNodeWithTag("reference").assertIsDisplayed()
        compose.onNodeWithText("tr-42").assertIsDisplayed()
        compose.onNodeWithText("ce téléphone (Samsung A15)").assertIsDisplayed()
        compose.onNodeWithText("Date").assertDoesNotExist()
        assertFalse(secureFlagSet())
    }

    @Test
    fun e17_deferred_showsTheDueTime_notACountdown_andCancels() {
        var cancel = false
        show {
            DeferredScreen(
                DailyStep.Deferred("tr-42", 25000, "677000456", "Jean-Paul NKOA", "2026-10-01T16:00:00Z"),
                onCancel = { cancel = true },
                onHome = {},
                zone = DOUALA,
            )
        }
        compose.onNodeWithText("Envoi programmé").assertIsDisplayed()
        compose.onNodeWithText("Cette opération est inhabituelle. Par sécurité, elle sera exécutée le 1 oct. à 17:00.").assertIsDisplayed()
        compose.onNodeWithText("25 000 FCFA → Jean-Paul NKOA").assertIsDisplayed()
        compose.onNodeWithTag("cancel-transfer").performClick()
        assertTrue(cancel)
    }

    @Test
    fun e17_cancelled_andLostCancelResponse() {
        var step by androidx.compose.runtime.mutableStateOf(
            DailyStep.Deferred("tr-42", 25000, "677000456", null, null, problem = Problem(DailyError.UNREACHABLE)),
        )
        show { DeferredScreen(step, onCancel = {}, onHome = {}, zone = DOUALA) }
        compose.onNodeWithText("Impossible de confirmer l'annulation. Vérifiez votre historique.").assertIsDisplayed()
        compose.onNodeWithText("25 000 FCFA → 677 000 456").assertIsDisplayed()
        step = step.copy(cancelled = true, problem = null)
        compose.onNodeWithTag("cancelled").assertIsDisplayed()
        compose.onNodeWithTag("cancel-transfer").assertDoesNotExist()
    }

    @Test
    fun e18_messagesByReasonCode() {
        var step by androidx.compose.runtime.mutableStateOf(DailyStep.Stopped(Outcome.FROZEN))
        show { StoppedScreen(step, onHome = {}, onHistory = {}) }
        compose.onNodeWithText("Opération bloquée").assertIsDisplayed()
        compose.onNodeWithTag("what-to-do").assertIsDisplayed()
        step = DailyStep.Stopped(Outcome.REJECTED)
        compose.onNodeWithText("Opération rejetée").assertIsDisplayed()
        compose.onNodeWithText("Réessayez.").assertIsDisplayed()
        compose.onNodeWithText("téléphone lié", substring = true).assertDoesNotExist()
        step = DailyStep.Stopped(Outcome.REJECTED_KEY)
        compose.onNodeWithText("Réessayez depuis votre téléphone lié.").assertIsDisplayed()
        step = DailyStep.Stopped(Outcome.UNKNOWN)
        compose.onNodeWithText("Impossible de confirmer l'envoi. Vérifiez votre historique.").assertIsDisplayed()
        compose.onNodeWithTag("history").assertIsDisplayed()
    }

    private fun formatXafForTest(v: Long) = cm.identishield.mobile.enrolment.formatXaf(v)
}
