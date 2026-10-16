package cm.identishield.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.daily.AmountScreen
import cm.identishield.mobile.daily.DailyHomeScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.HistoryScreen
import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

private val ACCOUNT = Account("acc-000123", "677000123", "Amina NDONGO ÉLOMO", 152300, "TRUSTED", emptyList())
private val HOLDER = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = false)

/**
 * Carte B-UI : mise en page adaptative (WindowSizeClass, ADR-037 amendement B-UI). Largeur compacte →
 * barre de navigation basse ; moyenne et étendue → rail latéral ; colonne de contenu bornée et centrée ;
 * rien n'est coupé en paysage ni en police à 200 % (le contenu défile).
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun home() =
        compose.setContent {
            IdentiShieldTheme { DailyHomeScreen(ACCOUNT, "Samsung A15", onSend = {}, onHistory = {}, onAlerts = {}) }
        }

    @Test
    @Config(qualifiers = "w360dp-h760dp")
    fun compactPhone_usesTheBottomBar_neverTheRail() {
        home()
        compose.onNodeWithTag("bottom-nav").assertIsDisplayed()
        compose.onNodeWithTag("nav-rail").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun mediumWidth_usesTheRail_insteadOfTheBottomBar() {
        home()
        compose.onNodeWithTag("nav-rail").assertIsDisplayed()
        compose.onNodeWithTag("bottom-nav").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun expandedTablet_usesTheRail_andCentresAColumnOfAtMost600dp() {
        home()
        compose.onNodeWithTag("nav-rail").assertIsDisplayed()
        val card = compose.onNodeWithTag("balance-card").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        val rail = compose.onNodeWithTag("nav-rail").getBoundsInRoot()
        assertTrue("colonne bornée : ${card.right - card.left}", card.right - card.left <= Dimens.ContentMaxWidth)
        val leftMargin = card.left - rail.right
        val rightMargin = root.right - card.right
        assertTrue("colonne centrée : $leftMargin / $rightMargin", abs((leftMargin - rightMargin).value) <= 1f)
    }

    @Test
    @Config(qualifiers = "w760dp-h360dp-land")
    fun phoneLandscape_usesTheRail_andTheKeypadScreenScrollsToItsButton() {
        compose.setContent {
            IdentiShieldTheme { AmountScreen(DailyStep.Amount(HOLDER, 152300), BuildConfig.QUICK_AMOUNTS_XAF.toList(), onSubmit = {}, onBack = {}) }
        }
        assertReachableInsideTheWindow("key-0")
        assertReachableInsideTheWindow("primary")
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp")
    fun fontAt200Percent_nothingIsCut_theButtonIsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                IdentiShieldTheme { AmountScreen(DailyStep.Amount(HOLDER, 152300), BuildConfig.QUICK_AMOUNTS_XAF.toList(), onSubmit = {}, onBack = {}) }
            }
        }
        assertReachableInsideTheWindow("primary")
    }

    /** Après défilement, le nœud tient ENTIER dans la fenêtre (assertIsDisplayed accepte un nœud débordant). */
    private fun assertReachableInsideTheWindow(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        val node = compose.onNodeWithTag(tag).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("$tag hors de la fenêtre : $node dans $root", node.top >= root.top && node.bottom <= root.bottom)
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp")
    fun securityDestination_isUnavailable_currentTabSelected_otherTabsActive() {
        home()
        compose.onNodeWithTag("nav-security").assertIsNotEnabled()
        compose.onNodeWithTag("nav-home").assertIsSelected()
        compose.onNodeWithTag("nav-history").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun securityDestination_isUnavailable_onTheRailToo() {
        compose.setContent { IdentiShieldTheme { HistoryScreen(DailyStep.History(emptyList()), onBack = {}, onHome = {}, onSend = {}) } }
        compose.onNodeWithTag("nav-security").assertIsNotEnabled()
        compose.onNodeWithTag("nav-alerts").assertIsNotEnabled() // aucune action depuis l'historique : jamais un lien mort
        compose.onNodeWithTag("nav-history").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp")
    fun balance_visibleOnOpening_theEyeHidesItAndShowsItAgain() {
        home()
        compose.onNodeWithTag("balance").assertIsDisplayed()
        compose.onNodeWithTag("balance-toggle").performClick()
        compose.onAllNodes(hasText("152 300")).assertCountEquals(0)
        compose.onNodeWithTag("balance-toggle").performClick()
        compose.onAllNodes(hasText("152 300")).assertCountEquals(1)
    }
}
