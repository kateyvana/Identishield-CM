package cm.identishield.core.mobile;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.PinStore.AccountPin;
import cm.identishield.core.mobile.SessionTokens.IssuedSession;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Ouverture de session, flux B (carte B4 ; ISC-MOB-SPEC §6 ; contracts/mobile-api.yaml) : défi →
 * numéro + PIN + défi signé par la clé de l'appareil lié → jeton. Le PIN est vérifié AVANT toute
 * question au PDP, en temps constant, et compté par origine réseau (P-11) ; le PDP vérifie la
 * signature et consomme le défi (son propre nonce) ; le cœur arbitre.
 *
 * <p>Revue ligne à ligne requise : secrets (PIN), défi, verdict de signature.
 */
// @implements ES-22
// @implements ES-26
@Service
public class MobileSession {

  /** Ce que le PDP a conclu de la preuve, tel que le cœur l'arbitre. */
  public enum Proof {
    /** Signature valide par la clé ACTIVE de ce compte, défi consommé : une session est émise. */
    VALID,
    /** Une clé RÉVOQUÉE porte cet identifiant : l'application doit se réenrôler (KEY_REVOKED). */
    REVOKED,
    /** Toute autre faute : clé inconnue, d'un autre compte, signature fausse, défi inconnu. */
    INVALID,
    /** PDP injoignable ou réponse hors contrat : rien n'est conclu. */
    UNAVAILABLE
  }

  /** Verdict et, s'il est VALID, la session émise. */
  public record Outcome(Proof proof, IssuedSession session) {}

  /** Même forme qu'un nonce du PDP : 128 bits en hexadécimal minuscule. */
  private static final int DECOY_BYTES = 16;

  private final PinStore pins;
  private final PinHasher hasher;
  private final PinAttempts attempts;
  private final DeviceClient device;
  private final SessionTokens sessions;
  private final SecureRandom random = new SecureRandom();

  public MobileSession(
      PinStore pins,
      PinHasher hasher,
      PinAttempts attempts,
      DeviceClient device,
      SessionTokens sessions) {
    this.pins = pins;
    this.hasher = hasher;
    this.attempts = attempts;
    this.device = device;
    this.sessions = sessions;
  }

  /**
   * GET /session/challenge : le défi est un nonce du PDP, lié au compte de la clé ACTIVE qui porte
   * cet identifiant (décision du 2026-10-24, point 4). Identifiant sans clé ACTIVE : un défi de
   * MÊME forme est rendu, qui ne vaudra rien à /session ; l'appelant n'apprend pas si l'appareil
   * existe (ES-22). PDP injoignable : 503.
   */
  public Answer challenge(String deviceId) {
    Reply r = device.nonce(deviceId);
    if (r.status() == 200 && r.text("nonce") != null) {
      return Answer.ok(Map.of("challenge", r.text("nonce")));
    }
    if (r.status() == 404) {
      byte[] decoy = new byte[DECOY_BYTES];
      random.nextBytes(decoy);
      return Answer.ok(Map.of("challenge", HexFormat.of().formatHex(decoy)));
    }
    return Answer.unavailable();
  }

  /**
   * POST /session. Origine bloquée (P-11) : 429 avant tout calcul. PIN faux ou compte inconnu : la
   * MÊME réponse, après le même calcul (ES-22), et l'échec compte pour l'origine. Puis le PDP
   * reconnaît, ou non, la clé.
   */
  public Answer open(
      String msisdn,
      String pin,
      String deviceId,
      String challenge,
      String signature,
      String origin) {
    Optional<Long> locked = attempts.lockedFor(origin);
    if (locked.isPresent()) {
      return Answer.throttled(locked.get());
    }
    Optional<AccountPin> account = pins.find(msisdn);
    if (!hasher.matches(pin, account.map(AccountPin::pin).orElse(null))) {
      attempts.failed(origin);
      return Answer.refused(401, "CREDENTIALS_INVALID");
    }
    attempts.succeeded(origin);
    Outcome o = prove(account.orElseThrow().accountId(), deviceId, challenge, signature);
    return switch (o.proof()) {
      case VALID ->
          Answer.ok(
              Map.of(
                  "sessionToken",
                  o.session().token(),
                  "expiresInSeconds",
                  o.session().expiresInSeconds()));
      case REVOKED -> Answer.refused(403, "KEY_REVOKED");
      case INVALID -> Answer.refused(401, "CREDENTIALS_INVALID");
      case UNAVAILABLE -> Answer.unavailable();
    };
  }

  /**
   * Le PDP vérifie la signature du défi par la clé ACTIVE {@code deviceId} du compte et consomme le
   * défi ; VALID émet la session. Partagé avec la branche SESSION de /enrolment/start.
   */
  public Outcome prove(UUID accountId, String deviceId, String challenge, String signature) {
    Reply r = device.verify(accountId.toString(), deviceId, challenge, signature);
    if (r.status() != 200 || r.text("verdict") == null) {
      return new Outcome(Proof.UNAVAILABLE, null);
    }
    return switch (r.text("verdict")) {
      case "VALID" -> new Outcome(Proof.VALID, sessions.issue(accountId, deviceId));
      case "REVOKED" -> new Outcome(Proof.REVOKED, null);
      default -> new Outcome(Proof.INVALID, null);
    };
  }
}
