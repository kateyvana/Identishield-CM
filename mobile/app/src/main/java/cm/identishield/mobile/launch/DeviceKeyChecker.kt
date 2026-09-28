package cm.identishield.mobile.launch

import java.security.KeyStore

/** MOB-F-01 : au lancement, l'application regarde si une clé existe dans la puce. */
fun interface DeviceKeyChecker {
    fun hasDeviceKey(): Boolean
}

/** Vérification locale dans l'Android Keystore : seul l'alias est connu de l'application. */
class AndroidKeystoreKeyChecker(
    private val alias: String = DEVICE_KEY_ALIAS,
) : DeviceKeyChecker {
    override fun hasDeviceKey(): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.containsAlias(alias)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEVICE_KEY_ALIAS = "isc-device-key"
    }
}
