package cm.identishield.mobile.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Palette des maquettes (docs/02-specifications/mobile/maquettes/README.md), inchangée. Chaque couleur
// a un sens : le vert n'apparaît que pour un état vérifié par le serveur.
val Navy = Color(0xFF10375C) // dominante, bouton d'action
val ProtectedGreen = Color(0xFF1B998B) // « appareil protégé »
val Amber = Color(0xFFF2A93B) // alerte
val Danger = Color(0xFFD7263D) // danger, refus
val Background = Color(0xFFF4F7FB)
val Ink = Color(0xFF14213D) // texte

// Neutres ajoutés (B-UI) : surfaces, filets et texte secondaire, sans nouvelle teinte.
val Surface = Color(0xFFFFFFFF)
val Border = Color(0xFFD5DCE6) // filet décoratif, fond du bouton désactivé
val FieldBorder = Color(0xFF7D8799) // contour d'un champ de saisie : 3,62:1 sur Surface, 3,37:1 sur Background (≥ 3:1)
val TextSecondary = Color(0xFF52607A) // gris-bleu : 6,34:1 sur Surface, 5,90:1 sur Background, 4,59:1 sur Border

// Variantes assombries (même teinte, luminosité réduite) quand l'ambre ou le vert portent du TEXTE :
// l'ambre pur fait 2,00:1 sur blanc, le vert pur 3,51:1, sous le seuil AA de 4,5:1.
val AmberText = Color(0xFF975F0A) // 5,31:1 sur Surface, 4,94:1 sur Background, 4,53:1 sur AmberTint
val ProtectedGreenText = Color(0xFF15766B) // 5,48:1 sur Surface, 5,10:1 sur Background, 4,58:1 sur GreenTint

/** Fonds dilués des encadrés et des pastilles (le texte posé dessus prend la variante assombrie). */
val AmberTint = Amber.copy(alpha = 0.14f)
val GreenTint = ProtectedGreen.copy(alpha = 0.10f)
val NavyTint = Navy.copy(alpha = 0.06f)

/** Fond d'un disque de pictogramme : sa couleur à 12 %. */
const val DISC_TINT_ALPHA = 0.12f

/** Opacité d'un élément indisponible (valeur des composants Material désactivés). */
const val DISABLED_ALPHA = 0.38f

/**
 * Ton d'une pastille ou d'un encadré : couleur du texte, du fond, du contour. Le texte rouge reste sur
 * fond blanc (4,96:1) : sur un fond rouge dilué il tomberait à 4,09:1.
 */
enum class Tone(
    val text: Color,
    val fill: Color,
    val stroke: Color,
) {
    NEUTRAL(Navy, NavyTint, NavyTint), // 10,85:1
    MUTED(TextSecondary, Background, Border),
    WARNING(AmberText, AmberTint, Amber.copy(alpha = 0.45f)),
    DANGER(Danger, Surface, Danger.copy(alpha = 0.5f)),
    VERIFIED(ProtectedGreenText, GreenTint, ProtectedGreen.copy(alpha = 0.3f)),
}

/** Espacements : multiples de 4 dp, les seuls utilisés par les écrans. */
object Spacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

/** Dimensions des briques communes. */
object Dimens {
    val Hairline = 1.dp
    val TouchTarget = 48.dp // cible tactile minimale (Material, charte point 8)
    val Button = 56.dp
    val Key = 64.dp
    val HeaderBar = 56.dp
    val Icon = 24.dp
    val SmallIcon = 18.dp // cadenas « capture d'écran bloquée »
    val PinDot = 20.dp
    val Disc = 96.dp
    val DiscIcon = 44.dp
    val Logo = 120.dp
    val LogoLarge = 140.dp
    val LogoHalo = 200.dp // zone de l'onde autour du logo d'E-01
    val HeroOverlap = 48.dp // part de la carte du solde qui chevauche l'en-tête
    val ContentMaxWidth = 600.dp // au-delà, la colonne est centrée (tablette, paysage)
}

private val Tabular = "tnum" // chiffres à chasse fixe : les montants s'alignent

/** Échelle typographique (police système Roboto). */
val IdentiShieldTypography =
    Typography(
        displayLarge = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular),
        headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold), // titre d'écran
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal), // touche du pavé
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold), // bandeau d'écran
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold), // titre de carte
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp), // corps
        bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), // légende
        labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), // bouton
        labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), // libellé de champ, pastille
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium), // navigation
    )

/** Montants et codes : hors de l'échelle Material, mêmes règles (chiffres à chasse fixe). */
object AmountType {
    val Hero = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular)
    val Large = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular)
    val Row = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tabular)
    val Currency = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val Code = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    val Wordmark = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)

    /** Resserrement de l'espace de groupe des montants (≈ espace fine). */
    val GroupSpace = (-0.08).em
}

val IdentiShieldShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp), // touche, case
        medium = RoundedCornerShape(16.dp), // champ, bouton, encadré
        large = RoundedCornerShape(20.dp), // carte
        extraLarge = RoundedCornerShape(28.dp), // bord bas de l'en-tête d'accueil
    )

/** Mouvement : durées et courbe des animations (aucune ne retarde le parcours). */
object Motion {
    const val ENTER_MS = 900 // entrée du logo, du nom et de la devise (E-01)
    const val PULSE_MS = 1800 // onde « vérification en cours » autour du logo
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // courbe Material 3
    const val ENTER_SCALE = 0.86f // taille de départ du logo
    const val PULSE_ALPHA = 0.16f // opacité de l'onde à son départ
}

/** Pastille (badge, montant rapide) : extrémités entièrement arrondies. */
val PillShape = RoundedCornerShape(50)

private val IdentiShieldColors =
    lightColorScheme(
        primary = Navy,
        onPrimary = Surface,
        secondary = ProtectedGreen,
        onSecondary = Surface,
        tertiary = Amber,
        error = Danger,
        background = Background,
        onBackground = Ink,
        surface = Surface,
        onSurface = Ink,
        onSurfaceVariant = TextSecondary,
        outline = Border,
        outlineVariant = Border,
        secondaryContainer = NavyTint, // indicateur de l'onglet actif (barre, rail)
    )

@Composable
fun IdentiShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IdentiShieldColors, typography = IdentiShieldTypography, shapes = IdentiShieldShapes, content = content)
}
