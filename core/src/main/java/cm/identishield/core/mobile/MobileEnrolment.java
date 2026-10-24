package cm.identishield.core.mobile;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.Enrolments.Enrolment;
import cm.identishield.core.mobile.PinStore.AccountPin;
import cm.identishield.core.mobile.SessionTokens.IssuedSession;
import cm.identishield.core.otp.OtpEmission;
import cm.identishield.core.otp.OtpOutcome;
import cm.identishield.core.otp.OtpService;
import cm.identishield.core.otp.SmsDeliveryException;
import cm.identishield.core.otp.SmsGateway;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Premier enrôlement, flux A (carte B3a ; contracts/mobile-api.yaml) : numéro + PIN, OTP, liaison
 * de la clé EN ATTENTE, confirmation du code de récupération qui l'ACTIVE (invariant D4) et ouvre
 * la session. Les réponses portent le code de raison du contrat ; aucun secret n'est journalisé.
 *
 * <p>Revue ligne à ligne requise : machine à états de l'enrôlement, secrets (PIN, OTP, code).
 */
// @implements ES-22
// @implements ES-25
// @implements ES-26
@Service
public class MobileEnrolment {

  /**
   * Réponse de route : code HTTP, corps, Retry-After à poser (réponses transitoires : le délai du
   * disjoncteur PDP ; origine bloquée : les secondes restantes).
   */
  public record Answer(
      int status, Map<String, Object> body, boolean retryAfter, long throttledSeconds) {
    static Answer ok(Map<String, Object> body) {
      return new Answer(200, body, false, 0);
    }

    static Answer refused(int status, String reason) {
      return new Answer(status, Map.of("reason", reason), false, 0);
    }

    /** PDP ou réseau SMS injoignable : transitoire, l'enrôlement est GARDÉ à son étape. */
    static Answer unavailable() {
      return new Answer(503, Map.of("reason", "SERVICE_UNAVAILABLE"), true, 0);
    }

    /** Origine réseau bloquée après trop de PIN faux (P-11) : jamais le compte. */
    static Answer throttled(long seconds) {
      return new Answer(429, Map.of("reason", "THROTTLED"), false, Math.max(1, seconds));
    }
  }

  private static final int CHALLENGE_BYTES = 32;

  private final PinStore pins;
  private final PinHasher hasher;
  private final PinAttempts attempts;
  private final DeviceClient device;
  private final Enrolments enrolments;
  private final OtpService otp;
  private final SmsGateway sms;
  private final SessionTokens sessions;
  private final MobileSession session;
  private final RuleSet ruleSet;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final SecureRandom random = new SecureRandom();

  public MobileEnrolment(
      PinStore pins,
      PinHasher hasher,
      PinAttempts attempts,
      DeviceClient device,
      Enrolments enrolments,
      OtpService otp,
      SmsGateway sms,
      SessionTokens sessions,
      MobileSession session,
      RuleSet ruleSet,
      Clock clock,
      TransactionTemplate tx) {
    this.pins = pins;
    this.hasher = hasher;
    this.attempts = attempts;
    this.device = device;
    this.enrolments = enrolments;
    this.otp = otp;
    this.sms = sms;
    this.sessions = sessions;
    this.session = session;
    this.ruleSet = ruleSet;
    this.clock = clock;
    this.tx = tx;
  }

  /**
   * POST /enrolment/start (ES-17b, ES-26). Origine bloquée après trop de PIN faux (P-11) : 429
   * avant tout calcul. Le PIN est vérifié AVANT toute question au PDP : compte inconnu, sans PIN ou
   * PIN faux reçoivent la MÊME réponse, après le même calcul (ES-22), et l'échec compte pour
   * l'origine. Puis le PDP énonce le fait de liaison, le cœur arbitre : NONE et PENDING → OTP (une
   * clé EN ATTENTE jamais activée n'a lié personne) ; ACTIVE et signature du défi par la clé liée →
   * SESSION (carte B4) ; ACTIVE sans signature valide → RECOVERY. PDP injoignable : 503, JAMAIS
   * l'OTP.
   *
   * <p>Partiel : la branche RECOVERY ne porte pas encore d'enrolmentId (/enrolment/recover est la
   * carte B3b).
   */
  public Answer start(
      String msisdn,
      String pin,
      String origin,
      String deviceId,
      String challenge,
      String signature) {
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
    UUID accountId = account.orElseThrow().accountId();
    Reply fact = device.state(accountId.toString());
    if (fact.status() != 200 || fact.text("state") == null) {
      return Answer.unavailable();
    }
    // Le PDP énonce, le cœur arbitre.
    if ("ACTIVE".equals(fact.text("state"))) {
      if (deviceId != null && challenge != null && signature != null) {
        MobileSession.Outcome o = session.prove(accountId, deviceId, challenge, signature);
        switch (o.proof()) {
          case VALID -> {
            return Answer.ok(
                Map.of(
                    "next",
                    "SESSION",
                    "sessionToken",
                    o.session().token(),
                    "expiresInSeconds",
                    o.session().expiresInSeconds()));
          }
          case REVOKED -> {
            return Answer.refused(403, "KEY_REVOKED");
          }
          case UNAVAILABLE -> {
            return Answer.unavailable();
          }
          case INVALID -> {
            // Compte lié, signature qui ne vaut rien : la preuve la plus forte, le code.
          }
        }
      }
      // Flux D (carte B3b) : aucun OTP (EF-46) ; l'enrôlement s'ouvre à l'étape RECOVERY.
      Instant now = clock.instant();
      UUID id = enrolments.openRecovery(accountId, now, now.plus(ruleSet.enrolTtl()));
      return Answer.ok(Map.of("next", "RECOVERY", "enrolmentId", id.toString()));
    }
    Instant now = clock.instant();
    UUID id = enrolments.open(accountId, now, now.plus(ruleSet.enrolTtl()));
    OtpEmission emission = otp.issue(msisdn);
    try {
      sms.deliver(emission.targetImsi(), emission.code());
    } catch (SmsDeliveryException e) {
      return Answer.unavailable();
    }
    return Answer.ok(Map.of("next", "OTP", "enrolmentId", id.toString()));
  }

  /**
   * POST /enrolment/otp (EF-03). Après OTP_ATTEMPTS échecs, un nouvel OTP est obligatoire (410).
   * OTP accepté : la liaison peut commencer, et seulement alors (ES-25).
   */
  public Answer otp(UUID enrolmentId, String code) {
    Lookup l = lookup(enrolmentId, "OTP");
    if (l.refusal != null) {
      return l.refusal;
    }
    if (l.enrolment.otpFailures() >= ruleSet.otpAttempts()) {
      return Answer.refused(410, "OTP_EXPIRED");
    }
    OtpOutcome outcome = otp.verify(l.enrolment.msisdn(), code);
    if (outcome == OtpOutcome.REFUSED_EXPIRED) {
      return Answer.refused(410, "OTP_EXPIRED");
    }
    if (outcome != OtpOutcome.ACCEPTED) {
      enrolments.otpFailed(enrolmentId);
      return Answer.refused(401, "OTP_INVALID");
    }
    byte[] challenge = new byte[CHALLENGE_BYTES];
    random.nextBytes(challenge);
    String encoded = Base64.getEncoder().encodeToString(challenge);
    if (!enrolments.toBind(enrolmentId, encoded)) {
      return Answer.refused(410, "ENROLMENT_EXPIRED");
    }
    return Answer.ok(Map.of("next", "BIND", "attestationChallenge", encoded));
  }

  /**
   * POST /enrolment/recover (flux D, carte B3b ; ADR-041 : « aucune route de vérification de code »).
   * Cette route NE VÉRIFIE RIEN et ne garde rien : le code tapé est renvoyé par l'application à
   * /enrolment/complete, avec l'attestation, et c'est le PDP qui le vérifie en une seule fois, dans
   * /device/register (décision de la propriétaire du 2026-10-24, point 1 : le serveur ne détient
   * jamais un secret en clair, même transitoirement). Ici : le défi d'attestation est émis et
   * l'enrôlement passe à BIND. Le code reçu est ignoré.
   */
  // @implements ES-19
  public Answer recover(UUID enrolmentId) {
    Lookup l = lookup(enrolmentId, "RECOVERY");
    if (l.refusal != null) {
      return l.refusal;
    }
    byte[] challenge = new byte[CHALLENGE_BYTES];
    random.nextBytes(challenge);
    String encoded = Base64.getEncoder().encodeToString(challenge);
    if (!enrolments.toBind(enrolmentId, encoded, "RECOVERY")) {
      return Answer.refused(410, "ENROLMENT_EXPIRED");
    }
    return Answer.ok(Map.of("next", "BIND", "attestationChallenge", encoded));
  }

  /**
   * POST /enrolment/complete : la clé est enregistrée EN ATTENTE au PDP, qui émet le code de
   * récupération une seule fois. Elle ne signe rien tant que le code n'est pas confirmé (D4). Au
   * réenrôlement, {@code recoveryCode} (l'ancien code, retapé) part au PDP avec l'attestation et y
   * est vérifié ; le cœur ne le garde pas. {@code origin} et {@code installationId} : clés de
   * comptage des échecs (ADR-030), appliquées par le PDP.
   */
  public Answer complete(
      UUID enrolmentId,
      String publicKey,
      List<String> chain,
      String challengeSignature,
      String recoveryCode,
      String origin,
      String installationId) {
    Lookup l = lookup(enrolmentId, "BIND");
    if (l.refusal != null) {
      return l.refusal;
    }
    Enrolment e = l.enrolment;
    Reply r =
        device.register(
            e.accountId().toString(),
            publicKey,
            chain,
            e.attestationChallenge(),
            challengeSignature,
            recoveryCode,
            origin,
            installationId);
    switch (r.status()) {
      case 200 -> {
        String deviceId = r.text("deviceId");
        String code = r.text("recoveryCode");
        if (deviceId == null || code == null || !enrolments.toConfirm(enrolmentId, deviceId)) {
          return Answer.unavailable();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrolmentId", enrolmentId.toString());
        body.put("deviceId", deviceId);
        body.put("recoveryCode", code);
        return Answer.ok(body);
      }
      case 401 -> {
        // Le compte a une clé ACTIVE et le PDP n'a pas reconnu la preuve (code faux ou absent,
        // attestation ou signature refusée : il ne dit pas lequel, ES-22).
        if (recoveryCode != null) {
          // Réenrôlement : l'usager retape son code ; l'enrôlement reste en BIND. Le freinage est
          // celui du PDP (ADR-030), rendu par 429 ci-dessous.
          return Answer.refused(401, "RECOVERY_INVALID");
        }
        // Premier enrôlement sans code : une autre liaison a devancé celle-ci. Le renvoi vers
        // start route vers la preuve la PLUS FORTE dans les deux sens de la course : si
        // l'attaquant a gagné, l'usager légitime arrive sur RECOVERY et tape son code, la défense
        // prévue ; si c'est l'attaquant qui arrive ici, il est renvoyé vers RECOVERY et il lui
        // faut un code qu'il n'a pas. La course ne peut dégrader personne. Même réponse qu'un
        // enrôlement expiré (limite R1).
        enrolments.consume(enrolmentId, "BIND");
        return Answer.refused(410, "ENROLMENT_EXPIRED");
      }
      case 429 -> {
        return Answer.throttled(r.number("retryAfterSeconds"));
      }
      case 422 -> {
        String reason = r.text("code");
        return Answer.refused(
            422, "DEVICE_UNSUPPORTED".equals(reason) ? reason : "ATTESTATION_REJECTED");
      }
      case 400 -> {
        return Answer.refused(400, "REQUEST_INVALID");
      }
      default -> {
        // Injoignable ou réponse perdue : l'enrôlement reste en BIND. Si la clé a été
        // enregistrée et la réponse perdue, le nouvel essai la révoque (une seule EN ATTENTE).
        return Answer.unavailable();
      }
    }
  }

  /**
   * POST /recovery-code/confirm : le code retapé en entier ACTIVE la clé (D4), CONSOMME
   * l'enrôlement (toute reprise reçoit 410) et ouvre la session.
   *
   * <p>Réponse perdue (délai dépassé, PDP tombé après coup) : le cœur relit le fait de liaison.
   * ACTIVE → l'activation a réussi, il poursuit, sinon l'usager réessaierait avec un code déjà
   * consommé et se retrouverait verrouillé hors de son propre compte. PENDING ou NONE → l'échec est
   * réel : 503, l'enrôlement reste en CONFIRM.
   */
  public Answer confirm(UUID enrolmentId, String recoveryCode) {
    Lookup l = lookup(enrolmentId, "CONFIRM");
    if (l.refusal != null) {
      return l.refusal;
    }
    Enrolment e = l.enrolment;
    Reply r = device.activate(e.accountId().toString(), e.deviceId(), recoveryCode);
    if (r.status() == 401 && "RECOVERY_INVALID".equals(r.text("code"))) {
      return Answer.refused(401, "RECOVERY_INVALID");
    }
    if (r.status() == 400) {
      return Answer.refused(400, "REQUEST_INVALID");
    }
    if (r.status() != 200) {
      Reply fact = device.state(e.accountId().toString());
      if (fact.status() != 200 || !"ACTIVE".equals(fact.text("state"))) {
        return Answer.unavailable();
      }
    }
    IssuedSession session =
        tx.execute(
            s ->
                enrolments.consume(enrolmentId, "CONFIRM")
                    ? sessions.issue(e.accountId(), e.deviceId())
                    : null);
    if (session == null) {
      return Answer.refused(410, "ENROLMENT_EXPIRED");
    }
    return Answer.ok(
        Map.of("sessionToken", session.token(), "expiresInSeconds", session.expiresInSeconds()));
  }

  private record Lookup(Enrolment enrolment, Answer refusal) {}

  /**
   * Enrôlement inconnu ou à une autre étape : 400 (requête hors contrat). Expiré OU consommé : la
   * MÊME réponse 410, quelle qu'en soit la raison.
   */
  private Lookup lookup(UUID id, String stage) {
    Optional<Enrolment> found = enrolments.find(id);
    if (found.isEmpty()) {
      return new Lookup(null, Answer.refused(400, "REQUEST_INVALID"));
    }
    Enrolment e = found.get();
    if ("DONE".equals(e.stage()) || !e.expiresAt().isAfter(clock.instant())) {
      return new Lookup(null, Answer.refused(410, "ENROLMENT_EXPIRED"));
    }
    if (!stage.equals(e.stage())) {
      return new Lookup(null, Answer.refused(400, "REQUEST_INVALID"));
    }
    return new Lookup(e, null);
  }
}
