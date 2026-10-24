package cm.identishield.core.transfer;

/**
 * Preuve de possession de l'appareil lié, portée par une opération venue de l'application (carte
 * B6 ; ADR-018) : identifiant de la clé ACTIVE, signature SHA256withECDSA (DER, base64) de la chaîne
 * canonique {@code accountId|type|amountXaf|beneficiaryId|nonce}, et le nonce émis par le PDP. Le
 * cœur ne vérifie RIEN : il transmet les trois champs au PDP avec {@code proofType=SIGNATURE}, et
 * le veto {@code SignatureVerifier} décide (ES-02, ES-10, ES-20).
 *
 * <p>{@code signature} peut être nul : l'application n'a pas pu signer. Ce n'est pas une erreur de
 * requête mais une opération APP non signée, que le PDP refuse (REJECTED, veto SIGNATURE_INVALID,
 * entrée d'audit écrite). La route mobile n'envoie JAMAIS {@code proofType=NONE} : le PDP
 * accepterait une opération APP sans preuve.
 */
public record Proof(String deviceKeyId, String signature, String nonce) {}
