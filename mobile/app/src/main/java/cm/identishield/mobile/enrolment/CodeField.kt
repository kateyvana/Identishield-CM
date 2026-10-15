package cm.identishield.mobile.enrolment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.enrolment.EnrolmentViewModel.Companion.CODE_VISIBLE_TAIL
import cm.identishield.mobile.ui.theme.Ink

/** Groupes de la clé à l'affichage et à la saisie (« K7M-2QX ») : lisibilité sur papier. */
const val CODE_GROUP = 3

/** Caractère de masquage de la clé. */
const val CODE_MASK = '•'

/**
 * Affichage de la clé : groupes de [CODE_GROUP] séparés par un tiret ; si [visibleTail] est donné,
 * tous les caractères sauf les [visibleTail] derniers sont masqués. La valeur saisie reste brute
 * (ni tiret ni masque) ; seul l'affichage change.
 */
internal class CodeTransformation(
    private val visibleTail: Int?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        return TransformedText(
            AnnotatedString(maskedCode(raw, visibleTail)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset + if (offset == 0) 0 else (offset - 1) / CODE_GROUP

                override fun transformedToOriginal(offset: Int) = (offset - offset / (CODE_GROUP + 1)).coerceAtMost(raw.length)
            },
        )
    }
}

/** « K7M2QX » → « •••-•QX » (visibleTail = 2) ou « K7M-2QX » (visibleTail = null). */
fun maskedCode(
    raw: String,
    visibleTail: Int?,
): String {
    val shown = if (visibleTail == null) raw else raw.mapIndexed { i, c -> if (i < raw.length - visibleTail) CODE_MASK else c }.joinToString("")
    return shown.chunked(CODE_GROUP).joinToString("-")
}

/** Rappel masqué d'E-09 : [codeLength] caractères dont seuls les derniers ([tail]) sont montrés. */
fun codeReminder(
    codeLength: Int,
    tail: String,
): String = maskedCode(CODE_MASK.toString().repeat((codeLength - tail.length).coerceAtLeast(0)) + tail, null)

/**
 * Champ de saisie de la clé de récupération (E-09) : majuscules, lettres et chiffres seulement,
 * longueur bornée par [codeLength] ; ni copier ni coller (MOB-S-06) ; clavier « mot de passe »
 * (sans apprentissage) ; masqué sauf les [CODE_VISIBLE_TAIL] derniers caractères ; déclaré mot de
 * passe aux services d'accessibilité. Compromis documenté : un lecteur d'écran n'annonce que les
 * caractères encore visibles, jamais ceux déjà masqués.
 */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    codeLength: Int,
    tag: String,
) {
    CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
        BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(v.uppercase().filter { it.isLetterOrDigit() }.take(codeLength)) },
            singleLine = true,
            visualTransformation = CodeTransformation(CODE_VISIBLE_TAIL),
            textStyle = TextStyle(fontSize = 26.sp, color = Ink, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Hairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .semantics { password() }
                    .testTag(tag),
        )
    }
}
