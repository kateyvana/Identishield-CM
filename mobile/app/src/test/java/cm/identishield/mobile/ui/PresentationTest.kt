package cm.identishield.mobile.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.daily.AmountScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.Outcome
import cm.identishield.mobile.daily.StoppedScreen
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.launch.LaunchScreen
import cm.identishield.mobile.launch.LaunchTags
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Carte B-UI, finitions : espaces des textes composés ; animation du logo d'E-01 et réglage « animations coupées ». */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class PresentationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composedTexts_keepTheSpaceBetweenTheirParts() {
        val holder = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = false)
        compose.setContent {
            IdentiShieldTheme { AmountScreen(DailyStep.Amount(holder, 152300), BuildConfig.QUICK_AMOUNTS_XAF.toList(), onSubmit = {}, onBack = {}) }
        }
        compose.onNodeWithText("À Jean-Paul NKOA · 677 000 456").assertIsDisplayed() // et non « ÀJean-Paul »
    }

    @Test
    fun frozenAdvice_keepsTheSpaceAfterTheQuestion() {
        compose.setContent { IdentiShieldTheme { StoppedScreen(DailyStep.Stopped(Outcome.FROZEN), onHome = {}, onHistory = {}) } }
        compose.onNodeWithText("Que faire ? Si", substring = true).assertIsDisplayed() // et non « Que faire ?Si »
    }

    @Test
    fun launch_animatesTheLogo_withAPulseWhileChecking() {
        compose.setContent { IdentiShieldTheme { LaunchScreen() } }
        compose.onNodeWithTag(LaunchTags.PULSE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Logo IdentiShield : un bouclier protège un téléphone").assertIsDisplayed()
        compose.onNodeWithTag(LaunchTags.CHECKING).assertIsDisplayed()
    }

    @Test
    fun launch_animationsTurnedOff_nothingMoves_everythingIsShown() {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
        compose.setContent { IdentiShieldTheme { LaunchScreen() } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(LaunchTags.PULSE).assertDoesNotExist()
        compose.onNodeWithText("Votre téléphone est votre clé").assertIsDisplayed()
        compose.onNodeWithTag(LaunchTags.CHECKING).assertIsDisplayed()
    }
}
