package cm.identishield.mobile.daily

import cm.identishield.mobile.security.DeviceKeyInvalidatedException
import cm.identishield.mobile.security.DeviceKeyManager
import cm.identishield.mobile.security.LocalUnlock
import kotlinx.coroutines.CancellationException
import java.util.Base64

/** Résultat d'une signature locale : la signature en base64, ou la raison de l'échec. */
internal sealed interface Signed {
    data class Done(
        val base64: String,
    ) : Signed

    data class Failed(
        val error: DailyError,
    ) : Signed
}

/**
 * Fabrique la charge PUIS la signe après déverrouillage local (flux B, C et F). Un champ impossible
 * à signer (séparateur « | »…) échoue fermé AVANT toute requête ; une erreur de la puce ne plante
 * jamais ; une clé invalidée par une nouvelle biométrie est signalée comme telle (MOB-S-03).
 */
internal suspend fun signLocally(
    keys: DeviceKeyManager,
    unlock: LocalUnlock,
    payload: () -> ByteArray,
): Signed =
    try {
        val bytes = payload()
        val signature = unlock.unlockAndSign(keys.newSigner(), bytes)
        if (signature == null) Signed.Failed(DailyError.UNLOCK_CANCELLED) else Signed.Done(Base64.getEncoder().encodeToString(signature))
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceKeyInvalidatedException) {
        Signed.Failed(DailyError.KEY_INVALIDATED)
    } catch (e: Exception) {
        Signed.Failed(DailyError.UNEXPECTED)
    }
