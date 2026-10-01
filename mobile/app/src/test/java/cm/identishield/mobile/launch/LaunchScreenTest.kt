package cm.identishield.mobile.launch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test d'interface Compose d'E-01, exécuté sur la JVM (Robolectric) : aucun téléphone requis. */
@RunWith(AndroidJUnit4::class)
class LaunchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsLogoTaglineAndCheckingMessage() {
        compose.setContent { IdentiShieldTheme { LaunchScreen() } }
        compose.onNodeWithTag(LaunchTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithContentDescription("Logo IdentiShield : un bouclier protège un téléphone").assertIsDisplayed()
        compose.onNodeWithText("Votre téléphone est votre clé").assertIsDisplayed()
        compose.onNodeWithTag(LaunchTags.CHECKING).assertIsDisplayed()
        compose.onNodeWithText("Vérification de la clé de cet appareil…").assertIsDisplayed()
    }
}
