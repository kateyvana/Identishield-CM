package cm.identishield.mobile

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.daily.AuthorizeScreen
import cm.identishield.mobile.daily.DailyStep
import cm.identishield.mobile.daily.ReviewScreen
import cm.identishield.mobile.data.Holder
import cm.identishield.mobile.enrolment.ConfirmKeyScreen
import cm.identishield.mobile.enrolment.EnrolStep
import cm.identishield.mobile.enrolment.PinScreen
import cm.identishield.mobile.enrolment.RecoveryKeyScreen
import cm.identishield.mobile.enrolment.RecoveryScreen
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D11 / MOB-S-05 : la liste FLAG_SECURE d'ISC-MOB-SPEC v1.2 §13 (E-06, E-08, E-09, E-14, E-15,
 * E-20), vérifiée écran par écran dans un seul test : un écran ajouté à la liste sans protection,
 * ou une protection retirée, fait échouer ce test. La preuve sur appareil (capture = 0 octet) est
 * faite sur la tablette et rapportée dans RAPPORT_PASSE_2026-10-08.md.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class SecureScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val holder = Holder("677000456", "acc-000456", "Jean-Paul NKOA", newBeneficiary = true)

    /** Les six écrans de la liste de la spécification, dans l'ordre de la liste. */
    private val specList: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "E-06" to { PinScreen(EnrolStep.Pin("677000200"), onSubmit = {}, onBack = {}) },
            "E-08" to { RecoveryKeyScreen(EnrolStep.RecoveryKey("K7M2QX", lastDisplay = false), onWritten = {}) },
            "E-09" to { ConfirmKeyScreen(EnrolStep.Confirm("QX"), BuildConfig.CODE_LENGTH, onConfirm = {}) },
            "E-14" to { ReviewScreen(DailyStep.Confirm(holder, 5000), onConfirm = {}, onCancel = {}, onBack = {}) },
            "E-15" to { AuthorizeScreen(DailyStep.Authorize(holder, 5000), onSubmit = {}, onBack = {}) },
            "E-20" to { RecoveryScreen(EnrolStep.Recovery(), BuildConfig.CODE_LENGTH, onSubmit = {}, onLostKey = {}, onBack = {}) },
        )

    private fun secureFlagSet() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun everyScreenOfTheSpecList_setsFlagSecure() {
        var index by mutableStateOf(0)
        compose.setContent { IdentiShieldTheme { specList[index].second() } }
        specList.forEachIndexed { i, (tag, _) ->
            index = i
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertTrue("MOB-S-05 : FLAG_SECURE absent sur $tag", secureFlagSet())
        }
    }
}
