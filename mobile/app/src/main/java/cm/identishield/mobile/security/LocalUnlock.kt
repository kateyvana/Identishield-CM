package cm.identishield.mobile.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.Signature
import kotlin.coroutines.resume

/**
 * Déverrouillage LOCAL du téléphone (biométrie ou code), lié à UNE signature (CryptoObject).
 * Le serveur ne voit jamais ce déverrouillage : il ne reçoit que la signature (P-11).
 */
fun interface LocalUnlock {
    /** Signe [payload] après déverrouillage ; null si l'usager annule ou échoue. */
    suspend fun unlockAndSign(
        signer: Signature,
        payload: ByteArray,
    ): ByteArray?
}

class BiometricLocalUnlock(
    private val activity: FragmentActivity,
    private val title: String,
    private val subtitle: String,
) : LocalUnlock {
    override suspend fun unlockAndSign(
        signer: Signature,
        payload: ByteArray,
    ): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            // Une signature qui échoue remonte au ViewModel (jamais de plantage ici).
                            val signed =
                                runCatching {
                                    result.cryptoObject?.signature?.let {
                                        it.update(payload)
                                        it.sign()
                                    }
                                }
                            if (cont.isActive) cont.resumeWith(signed)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            val info =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signer))
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
}
