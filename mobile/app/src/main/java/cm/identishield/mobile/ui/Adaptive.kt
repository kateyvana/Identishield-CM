package cm.identishield.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * Classe de largeur de la fenêtre, par l'API standard (ADR-037, amendement B-UI) : compacte sous
 * 600 dp, moyenne de 600 à 840 dp, étendue au-delà. Au-delà de la largeur compacte, la navigation
 * passe de la barre basse au rail latéral (téléphone en paysage, tablette).
 */
@Composable
fun useNavigationRail(): Boolean {
    val configuration = LocalConfiguration.current
    return WindowSizeClass.BREAKPOINTS_V1
        .computeWindowSizeClass(configuration.screenWidthDp, configuration.screenHeightDp)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
}
