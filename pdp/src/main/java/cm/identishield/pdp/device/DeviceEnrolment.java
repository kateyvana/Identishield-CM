package cm.identishield.pdp.device;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.device.DeviceRegistry.DeviceKey;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import cm.identishield.pdp.trust.TrustStateMachine;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enrôlement d'un appareil côté PDP (ADR-041) : la clé n'est enregistrée qu'EN ATTENTE, et ne
 * devient ACTIVE qu'après la confirmation du code de récupération retapé en entier (invariant D4).
 * Un échec ne fait jamais progresser l'état.
 *
 * <p>Enregistrement, dans l'ordre : appelant bloqué (ES-21) → refus avant tout calcul ; invariant
 * R1 (amendement du 2026-10-17) : un compte qui possède déjà une clé ACTIVE EXIGE l'ancien code de
 * récupération, vérifié ICI, avec l'attestation, en une seule fois — jamais par une route à part,
 * qui serait un oracle ; attestation matérielle liée au défi émis par le cœur ({@link
 * AttestationVerifier}) ; signature de ce défi par la clé présentée. Seulement alors : clé EN
 * ATTENTE et code de récupération émis, dans une même transaction. Au réenrôlement, la clé ACTIVE
 * RESTE ACTIVE jusqu'à l'activation de la nouvelle (décision du 2026-10-24).
 *
 * <p>Réponse UNIFORME au réenrôlement : code faux, code absent, attestation refusée et défi
 * invalide donnent le MÊME refus, et toutes les vérifications sont faites quoi qu'il arrive (le
 * PBKDF2 du code comme l'attestation) pour que la durée ne dise pas lequel a échoué (ES-22) ;
 * chaque échec compte pour l'origine, l'installation et le compte (ADR-030). Seule une clé
 * LOGICIELLE reste nommée (DEVICE_UNSUPPORTED) : elle parle de l'appareil de l'appelant, pas du
 * code.
 *
 * <p>Gel des ré-enrôlements (ES-27, A1-4 ; décision du superviseur du 2026-10-25, L-B5-2) : pendant
 * {@code reenrolFrozenUntil} (posé par « ce n'est pas moi »), un ré-enrôlement reçoit EXACTEMENT le
 * refus d'un code faux — même contenu, même code, même durée, toutes les vérifications faites —
 * ES-22 l'emporte sur une réponse distincte. Le PDP compte la tentative (ES-24) ; aucune
 * restriction nouvelle sur le compte.
 *
 * <p>Audit SYMÉTRIQUE (décision du superviseur du 2026-10-25, T-AC-16) : tout refus d'un compte
 * lié, hors appelant bloqué, écrit UNE entrée {@code REENROL_REFUSED} avec UNE cause, la première
 * dans l'ordre des vérifications : DEVICE_UNSUPPORTED, REENROL_FROZEN, CODE_INVALID,
 * ATTESTATION_INVALID, SIGNATURE_INVALID. Même travail des deux côtés : la durée d'un refus ne
 * révèle pas l'état gelé. Le gel est lu en lecture seule ({@link TrustStateMachine#peek}), avant
 * tout calcul, pour chaque compte déjà lié.
 *
 * <p>Revue ligne à ligne requise : vérification de signature, machine à états de la clé.
 */
// @implements ES-25
// @implements ES-18
// @implements ES-19
// @implements ES-21
@Service
public class DeviceEnrolment {

  /** Issue d'un enregistrement. */
  public sealed interface Registration permits Registered, Refused {}

  /** Clé EN ATTENTE ; [recoveryCode] en clair, à transmettre une seule fois. */
  public record Registered(String deviceId, String recoveryCode) implements Registration {}

  /** Refus ; aucune écriture n'a eu lieu (hors compteurs d'échecs). */
  public record Refused(Refusal reason, long retryAfterSeconds) implements Registration {
    public Refused(Refusal reason) {
      this(reason, 0);
    }
  }

  public enum Refusal {
    /**
     * Compte déjà lié (R1) : code de récupération faux ou absent, attestation ou signature refusée
     * — sans dire lequel.
     */
    RECOVERY_INVALID,
    /** Appelant bloqué après trop d'échecs (ES-21) ; {@code retryAfterSeconds} dit combien. */
    THROTTLED,
    /** Chaîne, défi ou signature refusés (premier enrôlement). */
    ATTESTATION_REJECTED,
    /** Clé logicielle (puce absente, émulateur). */
    DEVICE_UNSUPPORTED
  }

  /** Activation refusée dans la transaction : annule la révocation faite juste avant. */
  private static final class ActivationRefused extends RuntimeException {
    ActivationRefused() {
      super(null, null, false, false);
    }
  }

  private final AttestationVerifier verifier;
  private final DeviceRegistry registry;
  private final RecoveryCodes codes;
  private final EnrolmentThrottle throttle;
  private final TrustStateMachine trust;
  private final AuditLog auditLog;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DeviceEnrolment(
      AttestationVerifier verifier,
      DeviceRegistry registry,
      RecoveryCodes codes,
      EnrolmentThrottle throttle,
      TrustStateMachine trust,
      AuditLog auditLog,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.verifier = verifier;
    this.registry = registry;
    this.codes = codes;
    this.throttle = throttle;
    this.trust = trust;
    this.auditLog = auditLog;
    this.tx = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  /**
   * @param recoveryCode l'ancien code de récupération, exigé si le compte a déjà une clé ACTIVE
   *     (réenrôlement, flux D) ; ignoré sinon
   * @param callerKeys clés comptées en cas d'échec de réenrôlement : origine réseau, identifiant
   *     d'installation s'il est connu (ADR-030) ; le compte est ajouté ici
   */
  public Registration register(
      String accountId,
      String publicKey,
      List<String> attestationChain,
      String attestationChallenge,
      String challengeSignature,
      String recoveryCode,
      List<Key> callerKeys) {
    Optional<DeviceKey> active = registry.findActive(accountId);
    if (active.isEmpty()) {
      return firstEnrolment(
          accountId, publicKey, attestationChain, attestationChallenge, challengeSignature);
    }
    List<Key> keys = new java.util.ArrayList<>(callerKeys);
    keys.add(Key.account(accountId));
    Optional<Long> locked = throttle.lockedFor(keys);
    if (locked.isPresent()) {
      return new Refused(Refusal.THROTTLED, locked.get());
    }
    // Gel des ré-enrôlements : lu pour CHAQUE compte lié, avant les calculs (même durée gelé ou
    // non).
    Instant now = clock.instant();
    Optional<Instant> frozenUntil =
        trust
            .peek(accountId)
            .map(TrustStateMachine.Trust::reenrolFrozenUntil)
            .filter(until -> now.isBefore(until));
    // Tout est vérifié, toujours, dans le même ordre : la durée ne désigne pas la faute.
    boolean codeOk = codes.matches(active.get().id(), recoveryCode == null ? "" : recoveryCode);
    byte[] challenge = decode(attestationChallenge);
    AttestationVerifier.Outcome outcome =
        challenge == null ? null : verifier.verify(attestationChain, publicKey, challenge);
    boolean signed = challenge != null && signedBy(publicKey, challenge, challengeSignature);
    // UNE cause, la première dans l'ordre des vérifications (décision du superviseur du 2026-10-25,
    // symétrie T-AC-16) : la clé LOGICIELLE d'abord, gel ou non (PR #110 : sa réponse parle de
    // l'appareil, pas du code, et ne doit pas révéler l'état gelé, ES-22/AC-16 ; contrat
    // device.yaml) ;
    // puis le gel, qui l'emporte sur le code : l'audit ne dit jamais « le code était juste »
    // pendant
    // un gel ; puis code, attestation, signature.
    String cause = refusalCause(outcome, frozenUntil.isPresent(), codeOk, signed);
    if (cause != null) {
      // Tout refus d'un compte lié fait le MÊME travail, au même endroit : compté (ES-24) et UNE
      // entrée d'audit (la durée d'un refus ne désigne ni la faute ni l'état gelé). Aucun secret.
      throttle.failed(keys);
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("event", "REENROL_REFUSED");
      p.put("accountId", accountId);
      p.put("cause", cause);
      frozenUntil.ifPresent(until -> p.put("reenrolFrozenUntil", until.toString()));
      p.put("occurredAt", now.toString());
      auditLog.append(p, now);
      return new Refused(
          CAUSE_DEVICE_UNSUPPORTED.equals(cause)
              ? Refusal.DEVICE_UNSUPPORTED
              : Refusal.RECOVERY_INVALID);
    }
    Registration r =
        tx.execute(
            status -> {
              String deviceId = registry.registerPending(accountId, publicKey, now);
              return new Registered(deviceId, codes.issue(deviceId, now));
            });
    throttle.succeeded(keys);
    return r;
  }

  static final String CAUSE_DEVICE_UNSUPPORTED = "DEVICE_UNSUPPORTED";
  static final String CAUSE_REENROL_FROZEN = "REENROL_FROZEN";
  static final String CAUSE_CODE_INVALID = "CODE_INVALID";
  static final String CAUSE_ATTESTATION_INVALID = "ATTESTATION_INVALID";
  static final String CAUSE_SIGNATURE_INVALID = "SIGNATURE_INVALID";

  /**
   * Cause d'audit d'un refus de ré-enrôlement, la première dans l'ordre des vérifications ; {@code
   * null} si rien ne s'oppose à l'enregistrement.
   */
  static String refusalCause(
      AttestationVerifier.Outcome outcome, boolean frozen, boolean codeOk, boolean signed) {
    if (outcome == AttestationVerifier.Outcome.SOFTWARE_KEY) {
      return CAUSE_DEVICE_UNSUPPORTED;
    }
    if (frozen) {
      return CAUSE_REENROL_FROZEN;
    }
    if (!codeOk) {
      return CAUSE_CODE_INVALID;
    }
    if (outcome != AttestationVerifier.Outcome.VALID) {
      return CAUSE_ATTESTATION_INVALID;
    }
    if (!signed) {
      return CAUSE_SIGNATURE_INVALID;
    }
    return null;
  }

  private Registration firstEnrolment(
      String accountId,
      String publicKey,
      List<String> attestationChain,
      String attestationChallenge,
      String challengeSignature) {
    byte[] challenge = decode(attestationChallenge);
    if (challenge == null) {
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
   * Vrai si la clé EN ATTENTE [deviceId] de [accountId] est désormais ACTIVE — et, au réenrôlement,
   * si la clé ACTIVE précédente est RÉVOQUÉE dans la MÊME transaction (ES-14b, ES-20 : jamais deux
   * ACTIVE, jamais zéro entre les deux). Tout échec (code erroné, appareil inconnu ou d'un autre
   * compte, clé qui n'est pas EN ATTENTE) rend faux, sans distinction, et laisse l'état inchangé :
   * la révocation est annulée avec la transaction.
   */
  public boolean activate(String accountId, String deviceId, String recoveryCode) {
    if (!codes.matches(deviceId, recoveryCode)) {
      return false;
    }
    Instant now = clock.instant();
    try {
      tx.executeWithoutResult(
          status -> {
            registry.lockAccount(accountId);
            registry.revokeActive(accountId, now);
            if (!registry.activate(accountId, deviceId)) {
              throw new ActivationRefused();
            }
          });
      return true;
    } catch (ActivationRefused e) {
      return false;
    }
  }

  private static byte[] decode(String base64) {
    try {
      return Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException | NullPointerException e) {
      return null;
    }
  }

  /** Signature ECDSA-SHA256 de [data] par [publicKey] (SubjectPublicKeyInfo base64) ? */
  private static boolean signedBy(String publicKey, byte[] data, String signatureBase64) {
    try {
      ECPublicKey key = EcP256.parse(publicKey);
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(key);
      s.update(data);
      return s.verify(Base64.getDecoder().decode(signatureBase64));
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
      return false;
    }
  }
}
