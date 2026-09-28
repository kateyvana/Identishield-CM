package cm.identishield.mobile.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import cm.identishield.mobile.launch.AndroidKeystoreKeyChecker
import cm.identishield.mobile.launch.DeviceKeyChecker
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Clé publique et chaîne d'attestation, prêtes pour /enrolment/complete (base64, DER). */
data class GeneratedKey(
    val publicKey: String,
    val attestationChain: List<String>,
)

/** La puce ne fournit qu'une clé logicielle : refusée pour le canal application (MOB-S-02). */
class InsecureKeyStorageException : Exception("clé non matérielle : refusée (MOB-S-02)")

/**
 * Clé de l'appareil dans l'Android Keystore. L'application ne garde qu'un alias ; la clé privée
 * ne sort jamais de la puce (P-9). Création UNIQUEMENT après validation de l'OTP par le serveur
 * (ES-25) : c'est le ViewModel qui garantit cet ordre.
 */
interface DeviceKeyManager : DeviceKeyChecker {
    /** Crée la paire EC P-256 avec le défi d'attestation du serveur (MOB-S-04). */
    fun generate(attestationChallenge: ByteArray): GeneratedKey

    /** Signature prête à l'emploi ; son usage exige le déverrouillage local (MOB-S-03). */
    fun newSigner(): Signature

    fun delete()
}

class AndroidKeystoreDeviceKeyManager(
    private val alias: String = AndroidKeystoreKeyChecker.DEVICE_KEY_ALIAS,
) : DeviceKeyManager {
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun hasDeviceKey(): Boolean = keyStore().containsAlias(alias)

    override fun generate(attestationChallenge: ByteArray): GeneratedKey {
        delete() // une seule clé : jamais de clé orpheline d'une tentative précédente
        try {
            createPair(attestationChallenge, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            createPair(attestationChallenge, strongBox = false) // repli TEE (MOB-S-02)
        }
        if (!isHardwareBacked()) {
            delete()
            throw InsecureKeyStorageException()
        }
        val chain = keyStore().getCertificateChain(alias)
        return GeneratedKey(
            publicKey = b64(chain.first().publicKey.encoded), // SubjectPublicKeyInfo DER
            attestationChain = chain.map { b64(it.encoded) },
        )
    }

    override fun newSigner(): Signature =
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(keyStore().getKey(alias, null) as PrivateKey)
        }

    override fun delete() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun createPair(
        challenge: ByteArray,
        strongBox: Boolean,
    ) {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                // MOB-S-03 : chaque signature exige le déverrouillage local (biométrie OU code) ;
                // la clé est invalidée si une nouvelle biométrie est enregistrée.
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                ).setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun isHardwareBacked(): Boolean {
        val key = keyStore().getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CURVE = "secp256r1" // MOB-S-01, ADR-018
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA" // MOB-S-01, ADR-018
    }
}
