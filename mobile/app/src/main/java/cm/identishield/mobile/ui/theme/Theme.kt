package cm.identishield.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette des maquettes (docs/02-specifications/mobile/maquettes/README.md). Chaque couleur a un
// sens : le vert n'apparaît que pour un état vérifié par le serveur.
val Navy = Color(0xFF10375C) // dominante
val ProtectedGreen = Color(0xFF1B998B) // « appareil protégé »
val Amber = Color(0xFFF2A93B) // alerte
val Danger = Color(0xFFD7263D) // danger
val Background = Color(0xFFF4F7FB)
val Ink = Color(0xFF14213D) // texte

private val IdentiShieldColors =
    lightColorScheme(
        primary = Navy,
        onPrimary = Color.White,
        secondary = ProtectedGreen,
        onSecondary = Color.White,
        tertiary = Amber,
        error = Danger,
        background = Background,
        onBackground = Ink,
        surface = Background,
        onSurface = Ink,
    )

/** Police système d'Android (Roboto) : la typographie par défaut de Material 3 suffit. */
@Composable
fun IdentiShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IdentiShieldColors, content = content)
}
