package cm.identishield.mobile.security

import android.app.KeyguardManager
import android.content.Context

/**
 * D3 (charte, point 4) : précondition vérifiée AVANT de créer la clé. La clé exige le
 * déverrouillage local (MOB-S-03) ; sans verrou d'écran, la puce refuse de la créer. L'application
 * le constate d'abord et explique quoi faire, au lieu d'échouer avec un message générique.
 */
fun interface ScreenLock {
    /** Vrai si un verrouillage d'écran (code, schéma, mot de passe) est configuré. */
    fun isConfigured(): Boolean
}

class KeyguardScreenLock(
    context: Context,
) : ScreenLock {
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    override fun isConfigured(): Boolean = keyguard.isDeviceSecure
}
