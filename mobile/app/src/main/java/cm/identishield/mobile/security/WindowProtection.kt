package cm.identishield.mobile.security

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * MOB-S-05 : capture et enregistrement d'écran impossibles tant que l'écran sensible est affiché
 * (FLAG_SECURE masque aussi l'aperçu dans le sélecteur d'applications). Écrans concernés en M-06 :
 * E-06 (PIN), E-08 (clé de récupération), E-09 (confirmation).
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** MOB-S-07 : aucune touche n'est acceptée quand une autre fenêtre recouvre l'application. */
fun Activity.applyTouchProtection() {
    window.decorView.filterTouchesWhenObscured = true
}

private tailrec fun android.content.Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
