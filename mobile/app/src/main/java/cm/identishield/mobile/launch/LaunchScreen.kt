package cm.identishield.mobile.launch

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import cm.identishield.mobile.R
import cm.identishield.mobile.ui.theme.AmountType
import cm.identishield.mobile.ui.theme.Background
import cm.identishield.mobile.ui.theme.Dimens
import cm.identishield.mobile.ui.theme.Motion
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.TextSecondary

/** Identifiants de test stables (MOB-NF-06). */
object LaunchTags {
    const val SCREEN = "E-01"
    const val CHECKING = "E-01.checking"
    const val PULSE = "E-01.pulse"
}

/**
 * E-01 Lancement — maquette `maquettes/E01.png`. Un seul mouvement, qui dit ce que fait l'écran : le
 * bouclier apparaît, le nom et la devise montent à sa suite, puis une onde part du bouclier tant que
 * la clé est vérifiée. L'animation ne retarde rien (l'écran cède la place dès la fin de la
 * vérification) et disparaît si l'usager a coupé les animations dans les réglages d'Android.
 */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    val motion = motionEnabled()
    val entrance = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) entrance.animateTo(1f, tween(Motion.ENTER_MS, easing = Motion.EmphasizedDecelerate))
    }
    val rise = with(LocalDensity.current) { Spacing.M.toPx() }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Background)
                .padding(horizontal = Spacing.XXL)
                .testTag(LaunchTags.SCREEN),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(Dimens.LogoHalo)) {
            if (motion) Pulse()
            Image(
                painter = painterResource(R.drawable.logo_mark),
                contentDescription = stringResource(R.string.launch_logo_description),
                modifier =
                    Modifier
                        .size(Dimens.LogoLarge)
                        .graphicsLayer {
                            val p = entrance.value.phase(0f, 0.6f)
                            alpha = p
                            scaleX = Motion.ENTER_SCALE + (1f - Motion.ENTER_SCALE) * p
                            scaleY = scaleX
                        },
            )
        }
        Spacer(Modifier.height(Spacing.S))
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Navy)) { append(stringResource(R.string.brand_identi)) }
                    withStyle(SpanStyle(color = ProtectedGreen)) { append(stringResource(R.string.brand_shield)) }
                },
            style = AmountType.Wordmark,
            modifier = Modifier.rising(entrance.value.phase(0.25f, 0.8f), rise),
        )
        Spacer(Modifier.height(Spacing.S))
        Text(
            text = stringResource(R.string.launch_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            modifier = Modifier.rising(entrance.value.phase(0.4f, 1f), rise),
        )
        Spacer(Modifier.height(Spacing.XXL + Spacing.XL))
        Text(
            text = stringResource(R.string.launch_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier =
                Modifier
                    .rising(entrance.value.phase(0.55f, 1f), rise)
                    .testTag(LaunchTags.CHECKING),
        )
    }
}

/** Onde « vérification en cours » : un anneau bleu nuit qui s'élargit et s'efface, en boucle. */
@Composable
private fun Pulse() {
    val wave by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    Canvas(
        Modifier
            .size(Dimens.LogoHalo)
            .testTag(LaunchTags.PULSE),
    ) {
        val start = Dimens.LogoLarge.toPx() / 2f * 0.8f
        drawCircle(
            color = Navy,
            radius = start + (size.minDimension / 2f - start) * wave,
            alpha = Motion.PULSE_ALPHA * (1f - wave),
        )
    }
}

/** Progression locale d'un élément dans [start, end] de l'entrée globale (0 → 1). */
private fun Float.phase(
    start: Float,
    end: Float,
) = ((this - start) / (end - start)).coerceIn(0f, 1f)

/** Montée en fondu : opacité [p], décalage vertical de [distance] px à p = 0. */
private fun Modifier.rising(
    p: Float,
    distance: Float,
) = graphicsLayer {
    alpha = p
    translationY = (1f - p) * distance
}

/** Animations coupées dans les réglages (échelle de durée à 0) : rien ne bouge (accessibilité). */
@Composable
private fun motionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}
