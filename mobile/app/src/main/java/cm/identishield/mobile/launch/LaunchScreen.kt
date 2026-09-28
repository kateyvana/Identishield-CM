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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cm.identishield.mobile.R
import cm.identishield.mobile.ui.theme.Background
import cm.identishield.mobile.ui.theme.IdentiShieldTheme
import cm.identishield.mobile.ui.theme.Ink
import cm.identishield.mobile.ui.theme.Navy
import cm.identishield.mobile.ui.theme.ProtectedGreen

/** Identifiants de test stables (MOB-NF-06). */
object LaunchTags {
    const val SCREEN = "E-01"
    const val CHECKING = "E-01.checking"
}

/** E-01 Lancement — maquette `maquettes/E01.png`. */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    val secondary = Ink.copy(alpha = 0.65f)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Background)
                .padding(horizontal = 32.dp)
                .testTag(LaunchTags.SCREEN),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = stringResource(R.string.launch_logo_description),
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Navy)) { append(stringResource(R.string.brand_identi)) }
                    withStyle(SpanStyle(color = ProtectedGreen)) { append(stringResource(R.string.brand_shield)) }
                },
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.launch_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = secondary,
        )
        Spacer(Modifier.height(56.dp))
        Text(
            text = stringResource(R.string.launch_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = secondary,
            modifier = Modifier.testTag(LaunchTags.CHECKING),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LaunchScreenPreview() {
    IdentiShieldTheme { LaunchScreen() }
}
