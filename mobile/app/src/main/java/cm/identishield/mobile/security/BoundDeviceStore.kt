package cm.identishield.mobile.security

import android.content.Context
import androidx.core.content.edit

/** Ce que l'application garde de la liaison : le numéro et l'identifiant d'appareil, rien d'autre. */
data class BoundDevice(
    val msisdn: String,
    val deviceId: String,
)

/**
 * Données NON sensibles conservées entre deux lancements (ISC-MOB-SPEC §3, §4.1) : numéro mémorisé
 * et device_id. Jamais de solde, de nom, de PIN, de jeton ni de clé de récupération (MOB-S-10).
 */
interface BoundDeviceStore {
    fun load(): BoundDevice?

    fun save(device: BoundDevice)
}

/** Préférences privées de l'application ; sauvegarde et transfert désactivés (MOB-S-09). */
class PrefsBoundDeviceStore(
    context: Context,
) : BoundDeviceStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): BoundDevice? {
        val msisdn = prefs.getString(KEY_MSISDN, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        return BoundDevice(msisdn, deviceId)
    }

    // Écriture SYNCHRONE : la liaison doit être sur disque avant la suite ; sinon, un arrêt du
    // processus laisserait une clé sans device_id, et l'appareil passerait pour non lié.
    override fun save(device: BoundDevice) {
        prefs.edit(commit = true) {
            putString(KEY_MSISDN, device.msisdn)
            putString(KEY_DEVICE_ID, device.deviceId)
        }
    }

    companion object {
        const val FILE = "isc-bound-device"
        private const val KEY_MSISDN = "msisdn"
        private const val KEY_DEVICE_ID = "deviceId"
    }
}
