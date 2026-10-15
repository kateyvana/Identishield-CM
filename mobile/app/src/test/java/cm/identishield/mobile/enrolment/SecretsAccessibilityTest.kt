package cm.identishield.mobile.enrolment

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cm.identishield.mobile.BuildConfig
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D2 (charte, point 6) : FLAG_SECURE bloque les pixels, pas le texte. Les services d'accessibilité
 * (et `uiautomator`, qui lit le même arbre) ne doivent voir ni le code affiché (E-08), ni le code
 * saisi (E-09, E-20) au-delà de sa fin visible. Compromis documenté : un lecteur d'écran n'annonce
 * pas la clé ; l'usager malvoyant la lit à l'écran ou se fait aider hors application.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h760dp")
class SecretsAccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val length = BuildConfig.CODE_LENGTH
    private val code = "K7PQ3M".take(length)
    private val tail = code.takeLast(EnrolmentViewModel.CODE_VISIBLE_TAIL)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { IdentiShieldTheme { content() } }

    /**
     * Aucun nœud de l'arbre exposé aux services d'accessibilité ne porte [fragment] (texte, texte
     * éditable, description). C'est l'arbre par défaut : les enfants d'un nœud qui « efface » sa
     * sémantique n'y figurent pas, comme dans les AccessibilityNodeInfo lues par `uiautomator`.
     */
    private fun assertNowhereInTheTree(fragment: String) {
        compose
            .onAllNodes(
                SemanticsMatcher("porte « $fragment »") { node ->
                    val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
                    val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
                    val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    (texts + listOfNotNull(editable) + description).any { it.contains(fragment) }
                },
            ).assertCountEquals(0)
    }

    private fun passwordField(tag: String) = compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

    @Test
    fun e08_theDisplayedCode_isInvisibleToAccessibilityServices() {
        show { RecoveryKeyScreen(EnrolStep.RecoveryKey(code, lastDisplay = false), onWritten = {}) }
        compose.onNodeWithTag("recovery-code").assertIsDisplayed()
        code.chunked(CODE_GROUP).forEach { assertNowhereInTheTree(it) }
    }

    @Test
    fun e20_theTypedCode_isAPasswordField_andOnlyItsTailIsReadable() {
        show { RecoveryScreen(EnrolStep.Recovery(), length, onSubmit = {}, onLostKey = {}, onBack = {}) }
        compose.onNodeWithTag("recovery-input").performTextInput(code.lowercase())
        passwordField("recovery-input")
        assertNowhereInTheTree(code.dropLast(EnrolmentViewModel.CODE_VISIBLE_TAIL))
    }

    @Test
    fun e09_theRetypedCode_isAPasswordField_andOnlyItsTailIsReadable() {
        show { ConfirmKeyScreen(EnrolStep.Confirm(tail), length, onConfirm = {}) }
        compose.onNodeWithTag("confirm-input").performTextInput(code.lowercase())
        passwordField("confirm-input")
        assertNowhereInTheTree(code.dropLast(EnrolmentViewModel.CODE_VISIBLE_TAIL))
    }
}
