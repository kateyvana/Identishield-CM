package cm.identishield.mobile.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen
import cm.identishield.mobile.ui.theme.Spacing
import cm.identishield.mobile.ui.theme.TextSecondary

/** Identifiants de test stables (MOB-NF-06). */
object LaunchTags {
    const val SCREEN = "E-01"
    const val CHECKING = "E-01.checking"
}

/** E-01 Lancement — maquette `maquettes/E01.png`. */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
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
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier = Modifier.size(Dimens.LogoLarge),
        )
        Spacer(Modifier.height(Spacing.XL))
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Navy)) { append(stringResource(R.string.brand_identi)) }
                    withStyle(SpanStyle(color = ProtectedGreen)) { append(stringResource(R.string.brand_shield)) }
                },
            style = AmountType.Wordmark,
        )
        Spacer(Modifier.height(Spacing.S))
        Text(
            text = stringResource(R.string.launch_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(Spacing.XXL + Spacing.XL))
        Text(
            text = stringResource(R.string.launch_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.testTag(LaunchTags.CHECKING),
        )
    }
}
