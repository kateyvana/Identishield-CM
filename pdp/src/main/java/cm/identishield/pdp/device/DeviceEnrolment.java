package cm.identishield.pdp.device;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enrôlement d'un appareil côté PDP (ADR-041) : la clé n'est enregistrée qu'EN ATTENTE, et ne
 * devient ACTIVE qu'après la confirmation du code de récupération retapé en entier (invariant D4).
 * Un échec ne fait jamais progresser l'état.
 *
 * <p>Enregistrement, dans l'ordre : invariant R1 (un compte qui a déjà une clé ACTIVE est refusé ;
 * le réenrôlement, qui fournira l'ancien code, est la carte B3b) ; attestation matérielle liée au
 * défi émis par le cœur ({@link AttestationVerifier}) ; signature de ce défi par la clé présentée.
 * Seulement alors : clé EN ATTENTE et code de récupération émis, dans une même transaction.
 *
 * <p>Revue ligne à ligne requise : vérification de signature, machine à états de la clé.
 */
// @implements ES-25
// @implements ES-18
@Service
public class DeviceEnrolment {

  /** Issue d'un enregistrement. */
  public sealed interface Registration permits Registered, Refused {}

  /** Clé EN ATTENTE ; [recoveryCode] en clair, à transmettre une seule fois. */
  public record Registered(String deviceId, String recoveryCode) implements Registration {}

  /** Refus ; aucune écriture n'a eu lieu. */
  public record Refused(Refusal reason) implements Registration {}

  public enum Refusal {
    /** Le compte a déjà une clé ACTIVE (invariant R1). */
    ALREADY_BOUND,
    /** Chaîne, défi ou signature refusés. */
    ATTESTATION_REJECTED,
    /** Clé logicielle (puce absente, émulateur). */
    DEVICE_UNSUPPORTED
  }

  private final AttestationVerifier verifier;
  private final DeviceRegistry registry;
  private final RecoveryCodes codes;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DeviceEnrolment(
      AttestationVerifier verifier,
      DeviceRegistry registry,
      RecoveryCodes codes,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.verifier = verifier;
    this.registry = registry;
    this.codes = codes;
    this.tx = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  public Registration register(
      String accountId,
      String publicKey,
      List<String> attestationChain,
      String attestationChallenge,
      String challengeSignature) {
    if (registry.hasActive(accountId)) {
      return new Refused(Refusal.ALREADY_BOUND);
    }
    byte[] challenge;
    try {
      challenge = Base64.getDecoder().decode(attestationChallenge);
    } catch (IllegalArgumentException e) {
      return new Refused(Refusal.ATTESTATION_REJECTED);
    }
    AttestationVerifier.Outcome outcome = verifier.verify(attestationChain, publicKey, challenge);
    if (outcome == AttestationVerifier.Outcome.SOFTWARE_KEY) {
      return new Refused(Refusal.DEVICE_UNSUPPORTED);
    }
    if (outcome != AttestationVerifier.Outcome.VALID
        || !signedBy(publicKey, challenge, challengeSignature)) {
      return new Refused(Refusal.ATTESTATION_REJECTED);
    }
    Instant now = clock.instant();
    return tx.execute(
        status -> {
          String deviceId = registry.registerPending(accountId, publicKey, now);
          return new Registered(deviceId, codes.issue(deviceId, now));
        });
  }

  /**
   * Vrai si la clé EN ATTENTE [deviceId] de [accountId] est désormais ACTIVE. Tout échec (code
   * erroné, appareil inconnu ou d'un autre compte, clé qui n'est pas EN ATTENTE) rend faux, sans
   * distinction, et laisse l'état inchangé.
   */
  public boolean activate(String accountId, String deviceId, String recoveryCode) {
    if (!codes.matches(deviceId, recoveryCode)) {
      return false;
    }
    Boolean activated = tx.execute(status -> registry.activate(accountId, deviceId));
    return Boolean.TRUE.equals(activated);
  }

  /** Signature ECDSA-SHA256 de [data] par [publicKey] (SubjectPublicKeyInfo base64) ? */
  private static boolean signedBy(String publicKey, byte[] data, String signatureBase64) {
    try {
      ECPublicKey key = EcP256.parse(publicKey);
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(key);
      s.update(data);
      return s.verify(Base64.getDecoder().decode(signatureBase64));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
