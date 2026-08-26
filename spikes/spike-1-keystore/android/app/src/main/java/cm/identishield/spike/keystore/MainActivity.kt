package cm.identishield.spike.keystore

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * SPIKE-1 (spec §5.A) : une seule activite, aucune dependance hors SDK.
 * Code jetable — jamais importe par android/ ni par le produit.
 *
 * Toutes les sorties sont dupliquees dans logcat, tag SPIKE1 :
 *   adb logcat -s SPIKE1
 * Au lancement, l'app journalise automatiquement les empreintes SHA-256 des
 * trois cas de canonical (etape 1), sans aucune interaction.
 */
class MainActivity : Activity() {

    private val tag = "SPIKE1"
    private val alias = "spike1-key"
    private val ks: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private lateinit var out: TextView
    private lateinit var fAccount: EditText
    private lateinit var fType: EditText
    private lateinit var fAmount: EditText
    private lateinit var fBenef: EditText
    private lateinit var fNonce: EditText

    // Valeurs FIGEES, identiques a server/CanonicalFingerprint.java.
    private val cases = linkedMapOf(
        "CAS-1" to listOf(
            "5f1e7c1a-9d3b-4e6a-8c2f-0b7d9a4e6c1d", "P2P", "1",
            "ben-000045", "9f86d081884c7d659a2feaa0c55ad015",
        ),
        "CAS-2" to listOf(
            "0e7c9b2a-5d1f-4c3a-9b6e-2f8a4d7c1e5b", "CASH_OUT", "999999999",
            "Aminaé Bénédicte-Ngô", "a3f1c2d4e5b6978810fedcba98765432",
        ),
        "CAS-3" to listOf(
            "7b2d4f6a-1c3e-4a5b-8d9f-0a1b2c3d4e5f", "CASH_OUT", "25000",
            "ben-000045", "0123456789abcdef0123456789abcdef",
        ),
    )

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        fAccount = field(root, "accountId")
        fType = field(root, "type")
        fAmount = field(root, "amountXaf")
        fBenef = field(root, "beneficiaryId")
        fNonce = field(root, "nonce")
        cases.keys.forEach { name -> button(root, "Préremplir $name") { prefill(name) } }
        button(root, "1. Générer la clé") { generateKey() }
        button(root, "2. Infos clé (StrongBox, attestation)") { keyInfo() }
        button(root, "3. Exporter la clé publique (base64)") { exportPublic() }
        button(root, "4. Canonical : SHA-256 + signer") { signCanonical() }
        button(root, "5. Mesurer (100 signatures, p50/p95)") { measure() }
        button(root, "6. Tenter l'export de la clé PRIVÉE") { tryExportPrivate() }
        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })
        prefill("CAS-3")
        logDeviceInfo()
        logStepOneFingerprints()
    }

    private fun logDeviceInfo() {
        show(
            "appareil: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
    }

    /** Etape 1 : empreintes des trois cas, journalisees automatiquement. */
    private fun logStepOneFingerprints() {
        val sb = StringBuilder("ETAPE-1 empreintes du canonical (appareil) :\n")
        for ((name, v) in cases) {
            val canonical = v.joinToString("|")
            val bytes = canonical.toByteArray(Charsets.UTF_8)
            sb.append("$name octets=${bytes.size} sha256=${sha256Hex(bytes)}\n")
        }
        show(sb.toString().trimEnd())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun field(parent: LinearLayout, hint: String): EditText =
        EditText(this).apply { this.hint = hint; parent.addView(this) }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener {
                try {
                    onClick()
                } catch (e: Exception) {
                    show("EXCEPTION ${e.javaClass.name}: ${e.message}")
                }
            }
        })
    }

    private fun prefill(name: String) {
        val v = cases.getValue(name)
        fAccount.setText(v[0]); fType.setText(v[1]); fAmount.setText(v[2])
        fBenef.setText(v[3]); fNonce.setText(v[4])
        show("$name prérempli")
    }

    private fun canonical(): String = listOf(
        fAccount.text, fType.text, fAmount.text, fBenef.text, fNonce.text
    ).joinToString("|")

    private fun generateKey() {
        val gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge("spike-1-identishield".toByteArray())
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()

        val way = try {
            gen.initialize(spec(strongBox = true)); gen.generateKeyPair()
            "STRONGBOX"
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(strongBox = false)); gen.generateKeyPair()
            "TEE (repli : StrongBoxUnavailableException)"
        }
        show("Q1 clé générée — voie : $way")
    }

    private fun keyInfo() {
        val chain = ks.getCertificateChain(alias)
        show(
            "alias: $alias\n" +
                "Q2 chaîne d'attestation: ${chain?.size ?: 0} certificat(s)\n" +
                "FEATURE_STRONGBOX_KEYSTORE: " +
                packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        )
    }

    private fun exportPublic() {
        val pub = ks.getCertificate(alias).publicKey
        val b64 = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        copy("pubkey", b64)
        show("clé publique X.509 base64 (copiée) :\nPUBKEY=$b64")
    }

    private fun signCanonical() {
        val c = canonical()
        val bytes = c.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ks.getKey(alias, null) as PrivateKey)
            update(bytes)
        }.sign()
        val sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP)
        copy("signature", sigB64)
        show(
            "canonical : [$c]\noctets UTF8: ${bytes.size}\n" +
                "sha256     : ${sha256Hex(bytes)}\n" +
                "Q4 taille signature base64: ${sigB64.length} caractères " +
                "(${sig.size} octets DER)\nSIG=$sigB64"
        )
    }

    private fun measure() {
        val bytes = canonical().toByteArray(Charsets.UTF_8)
        val key = ks.getKey(alias, null) as PrivateKey
        val times = LongArray(100)
        repeat(100) { i ->
            val t0 = System.nanoTime()
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(key); update(bytes)
            }.sign()
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        show("Q3 latence signature : p50=${times[49]} ms  p95=${times[94]} ms (n=100)")
    }

    private fun tryExportPrivate() {
        val key = ks.getKey(alias, null) as PrivateKey
        val encoded = try {
            key.encoded?.let { "OCTETS EXPOSÉS (${it.size}) — la clé est exportable !" } ?: "null"
        } catch (e: Exception) {
            "EXCEPTION ${e.javaClass.name}: ${e.message}"
        }
        val spec = try {
            KeyFactory.getInstance(key.algorithm)
                .getKeySpec(key, PKCS8EncodedKeySpec::class.java)
            "PKCS8 OBTENU — la clé est exportable !"
        } catch (e: Exception) {
            "EXCEPTION ${e.javaClass.name}: ${e.message}"
        }
        show(
            "classe de la clé : ${key.javaClass.name}\n" +
                "PrivateKey.getEncoded() → $encoded\n" +
                "KeyFactory.getKeySpec(PKCS8) → $spec"
        )
    }

    private fun copy(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun show(msg: String) {
        Log.i(tag, msg)
        out.text = "$msg\n\n${out.text}"
    }
}
