package cm.identishield.mobile.security

/** Un champ ne peut pas entrer dans une chaîne signée : l'application refuse de signer (échec fermé). */
class UnsignableFieldException(
    field: String,
) : Exception("champ « $field » impossible à signer (ADR-018)")

/**
 * Octets exactement signés par la clé de l'appareil (ADR-018 et son amendement M-07). Chaînes à
 * ordre fixe, séparateur « | » sans espaces, UTF-8 sans BOM ; jamais de JSON sérialisé.
 * Un champ vide, bordé d'espaces, contenant « | » ou un retour à la ligne est REFUSÉ : sans
 * échappement, il rendrait la chaîne ambiguë.
 */
object SignedPayload {
    private val NONCE = Regex("^[0-9a-f]{32}$") // ADR-018 : 32 caractères hexadécimaux minuscules

    /** E-10 : les octets UTF-8 de `challenge` tels que reçus, sans aucun décodage. */
    fun sessionChallenge(challenge: String): ByteArray {
        if (challenge.isEmpty()) throw UnsignableFieldException("challenge")
        return challenge.toByteArray(Charsets.UTF_8)
    }

    /** E-15 : accountId|type|amountXaf|beneficiaryId|nonce (ADR-018). */
    fun transfer(
        accountId: String,
        type: String,
        amountXaf: Long,
        beneficiaryId: String,
        nonce: String,
    ): ByteArray {
        if (amountXaf < 1) throw UnsignableFieldException("amountXaf")
        return join(
            field("accountId", accountId),
            field("type", type),
            amountXaf.toString(),
            field("beneficiaryId", beneficiaryId),
            nonce(nonce),
        )
    }

    /** E-17 : accountId|CANCEL|transferId|nonce (amendement M-07 d'ADR-018). */
    fun cancel(
        accountId: String,
        transferId: String,
        nonce: String,
    ): ByteArray = join(field("accountId", accountId), CANCEL, field("transferId", transferId), nonce(nonce))

    /** E-24 « C'est moi » : accountId|ALERT_ACK|alertId|nonce (amendement M-08 d'ADR-018). */
    fun alertAck(
        accountId: String,
        alertId: String,
        nonce: String,
    ): ByteArray = join(field("accountId", accountId), ALERT_ACK, field("alertId", alertId), nonce(nonce))

    /** E-24 « Ce n'est pas moi » : accountId|ALERT_NOT_ME|alertId|nonce (amendement M-08 d'ADR-018). */
    fun alertNotMe(
        accountId: String,
        alertId: String,
        nonce: String,
    ): ByteArray = join(field("accountId", accountId), ALERT_NOT_ME, field("alertId", alertId), nonce(nonce))

    const val CANCEL = "CANCEL"
    const val ALERT_ACK = "ALERT_ACK"
    const val ALERT_NOT_ME = "ALERT_NOT_ME"
    private const val SEPARATOR = "|"

    private fun field(
        name: String,
        value: String,
    ): String {
        val unsafe =
            value.isEmpty() ||
                value != value.trim() ||
                value.contains(SEPARATOR) ||
                value.any { it == '\n' || it == '\r' }
        if (unsafe) throw UnsignableFieldException(name)
        return value
    }

    private fun nonce(value: String): String {
        if (!NONCE.matches(value)) throw UnsignableFieldException("nonce")
        return value
    }

    private fun join(vararg parts: String): ByteArray = parts.joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)
}
