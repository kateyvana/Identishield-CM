package cm.identishield.pdp.signature;

import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.DeviceKey;
import cm.identishield.pdp.device.EcP256;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Veto de signature (ISC-SAD SignatureVerifier ; ES-02, ES-10, ES-20). Recalcule le canonical
 * ADR-018 à partir de la transaction, vérifie la signature avec la clé ACTIVE enregistrée pour le
 * compte, puis consomme le nonce. Aucune autre responsabilité, et jamais par le moteur de règles.
 *
 * <p>Ordre : la signature d'abord, le nonce ensuite. Une signature invalide ne consomme pas le
 * nonce du titulaire ; une signature valide dont le nonce a déjà servi est un rejeu.
 */
// @implements ES-02
// @implements ES-20
@Component
public class SignatureVerifier {

  /** Ce que le serveur conclut de la preuve présentée. */
  public enum Verdict {
    /** Signature valide par la clé ACTIVE, nonce consommé par cette présentation. */
    VALID,
    /**
     * Signature absente, invalide, d'une autre clé ou d'une clé révoquée ; nonce inconnu ou expiré.
     */
    INVALID,
    /**
     * Signature valide, mais nonce déjà consommé : la même preuve est présentée une seconde fois.
     */
    REPLAYED
  }

  /** Les champs signés et la preuve, tels que reçus par /evaluate. */
  public record SignedTransaction(
      String accountId,
      String type,
      long amountXaf,
      String beneficiaryId,
      String deviceKeyId,
      String signature,
      String nonce) {}

  static final String ALGORITHM = "SHA256withECDSA"; // ADR-018

  private final DeviceRegistry registry;
  private final NonceStore nonces;

  public SignatureVerifier(DeviceRegistry registry, NonceStore nonces) {
    this.registry = registry;
    this.nonces = nonces;
  }

  public Verdict verify(SignedTransaction t) {
    if (isBlank(t.deviceKeyId()) || isBlank(t.signature()) || isBlank(t.nonce())) {
      return Verdict.INVALID;
    }
    Optional<DeviceKey> key = registry.findActive(t.accountId());
    // ES-20 : seule la clé ACTIVE du compte compte ; une clé révoquée ou inconnue est refusée.
    if (key.isEmpty() || !key.get().id().equals(t.deviceKeyId())) {
      return Verdict.INVALID;
    }
    if (!signatureMatches(key.get(), t)) {
      return Verdict.INVALID;
    }
    if (nonces.consume(t.nonce(), t.accountId())) {
      return Verdict.VALID;
    }
    return nonces.wasConsumed(t.nonce(), t.accountId()) ? Verdict.REPLAYED : Verdict.INVALID;
  }

  /**
   * Défi de session signé (carte B4 ; ISC-MOB-SPEC §6, flux B) : la clé ACTIVE {@code deviceId} du
   * compte a-t-elle signé les octets UTF-8 du défi tels que remis ? Le défi EST un nonce du PDP
   * émis pour ce compte (décision du 2026-10-24, point 4) : même ordre que pour une transaction, la
   * signature d'abord, le nonce ensuite ; un nonce déjà consommé ou expiré ne vaut rien. Une clé
   * RÉVOQUÉE portant cet identifiant est distinguée (le contrat mobile répond KEY_REVOKED) ; toute
   * autre faute reçoit INVALID, sans dire laquelle.
   */
  // @implements ES-20
  public ChallengeVerdict verifyChallenge(
      String accountId, String deviceId, String challenge, String signature) {
    return verifyChallenge(accountId, deviceId, challenge, signature, null);
  }

  /**
   * Variante (carte B6) : {@code payload} est une chaîne canonique ADR-018 qui CONTIENT le nonce
   * (ex. {@code accountId|CANCEL|transferId|nonce}) ; la signature porte sur ses octets UTF-8 et
   * c'est {@code nonce} qui est consommé. {@code nonce} nul : le défi est lui-même le nonce.
   */
  public ChallengeVerdict verifyChallenge(
      String accountId, String deviceId, String payload, String signature, String nonce) {
    String challenge = payload;
    String consumed = nonce == null ? payload : nonce;
    if (isBlank(accountId) || isBlank(deviceId) || isBlank(challenge) || isBlank(signature)) {
      return ChallengeVerdict.INVALID;
    }
    if (nonce != null && !payload.endsWith("|" + nonce)) {
      return ChallengeVerdict.INVALID; // le nonce consommé doit être celui que la chaîne porte
    }
    Optional<DeviceKey> key = registry.findActive(accountId);
    if (key.isEmpty() || !key.get().id().equals(deviceId)) {
      return registry.stateOf(accountId, deviceId).filter("REVOKED"::equals).isPresent()
          ? ChallengeVerdict.REVOKED
          : ChallengeVerdict.INVALID;
    }
    if (!challengeSignatureMatches(key.get(), challenge, signature)) {
      return ChallengeVerdict.INVALID;
    }
    return nonces.consume(consumed, accountId) ? ChallengeVerdict.VALID : ChallengeVerdict.INVALID;
  }

  /** Verdict d'un défi de session. */
  public enum ChallengeVerdict {
    VALID,
    INVALID,
    REVOKED
  }

  private static boolean challengeSignatureMatches(
      DeviceKey key, String challenge, String signature) {
    try {
      Signature verifier = Signature.getInstance(ALGORITHM);
      verifier.initVerify(EcP256.parse(key.publicKey()));
      verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(signature));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean signatureMatches(DeviceKey key, SignedTransaction t) {
    try {
      byte[] canonical =
          CanonicalPayload.transfer(
              t.accountId(), t.type(), t.amountXaf(), t.beneficiaryId(), t.nonce());
      Signature verifier = Signature.getInstance(ALGORITHM);
      verifier.initVerify(EcP256.parse(key.publicKey()));
      verifier.update(canonical);
      return verifier.verify(Base64.getDecoder().decode(t.signature()));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // Canonical impossible, base64 ou DER invalide, clé enregistrée illisible : jamais valide.
      return false;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
