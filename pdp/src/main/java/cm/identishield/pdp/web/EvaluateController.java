package cm.identishield.pdp.web;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.signature.SignatureVerifier.SignedTransaction;
import cm.identishield.pdp.signature.SignatureVerifier.Verdict;
import cm.identishield.pdp.time.MonotonicTimer;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /evaluate (EF-10, EF-11, ENF-03). Au mois 2 : décision ALLOW sans règle, portant la version
 * du jeu de règles (T2-03) et l'instant de l'horloge injectée (T2-02). Idempotence par decisionId :
 * un rejeu renvoie la même décision et n'écrit pas de seconde entrée d'audit. Exactement une entrée
 * d'audit chaînée par décision (le trigger en base chaîne in-lock ; vérification en T2-06).
 *
 * <p>M-09 : une preuve SIGNATURE passe d'abord par le veto {@link SignatureVerifier} (ES-02, ES-10,
 * ES-20), jamais par un moteur de règles. Signature invalide : REJECTED, veto SIGNATURE_INVALID,
 * journalisé. Nonce déjà consommé : 409 NONCE_CONSUMED (contrat), journalisé NONCE_REUSED.
 * L'idempotence passe AVANT le veto : un nouvel essai du même decisionId par le cœur reçoit la même
 * réponse, sans nouvelle consommation de nonce.
 *
 * <p>Joignable uniquement après une poignée de main mTLS réussie (OB-5).
 */
// @implements EF-10
// @implements EF-11
// @implements ENF-03
@RestController
public class EvaluateController {

  static final String PROOF_SIGNATURE = "SIGNATURE";
  static final String EVENT_NONCE_REUSED = "NONCE_REUSED";

  private final AuditLog auditLog;
  private final Clock clock;
  private final RuleSet ruleSet;
  private final SignatureVerifier signatureVerifier;

  public EvaluateController(
      AuditLog auditLog, Clock clock, RuleSet ruleSet, SignatureVerifier signatureVerifier) {
    this.auditLog = auditLog;
    this.clock = clock;
    this.ruleSet = ruleSet;
    this.signatureVerifier = signatureVerifier;
  }

  @PostMapping("/evaluate")
  public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest req) {
    long started = MonotonicTimer.nanos();

    // Idempotence (ENF-03) : rejeu d'un decisionId déjà décidé -> même décision.
    Optional<Map<String, Object>> already = auditLog.findByDecisionId(req.decisionId());
    if (already.isPresent()) {
      return fromPayload(already.get(), latencyMs(started));
    }

    Instant now = clock.instant();
    Verdict verdict =
        PROOF_SIGNATURE.equals(req.proofType()) ? signatureVerifier.verify(signed(req)) : null;
    if (verdict == Verdict.REPLAYED) {
      appendOnce(replayPayload(req, now), now);
      throw new NonceReplayedException();
    }
    Map<String, Object> payload = decisionPayload(req, now, verdict == Verdict.INVALID);
    try {
      auditLog.append(payload, now);
    } catch (DuplicateKeyException concurrentReplay) {
      // Deux appels simultanés du même decisionId : renvoyer la décision gagnante.
      return fromPayload(
          auditLog.findByDecisionId(req.decisionId()).orElseThrow(), latencyMs(started));
    }
    return fromPayload(payload, latencyMs(started));
  }

  private static SignedTransaction signed(EvaluateRequest req) {
    return new SignedTransaction(
        req.accountId(),
        req.type(),
        req.amountXaf(),
        req.beneficiaryId(),
        req.deviceKeyId(),
        req.signature(),
        req.nonce());
  }

  /** Un rejeu est journalisé une fois par decisionId ; un nouvel essai simultané ne double rien. */
  private void appendOnce(Map<String, Object> payload, Instant at) {
    try {
      auditLog.append(payload, at);
    } catch (DuplicateKeyException alreadyJournaled) {
      // même decisionId déjà journalisé : la réponse reste le 409
    }
  }

  /** Rejeu détecté (ES-10) : journalisé, sans décision. */
  private Map<String, Object> replayPayload(EvaluateRequest req, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("decisionId", req.decisionId());
    p.put("event", EVENT_NONCE_REUSED);
    p.put("evaluatedAt", DateTimeFormatter.ISO_INSTANT.format(at));
    p.put("accountId", req.accountId());
    p.put("deviceKeyId", req.deviceKeyId());
    p.put("nonce", req.nonce());
    return p;
  }

  /**
   * Décision squelette (aucune règle) + contexte de la requête, pour l'audit. Veto de signature :
   * REJECTED, SIGNATURE_INVALID (ES-02) ; aucune règle n'est évaluée.
   */
  private Map<String, Object> decisionPayload(EvaluateRequest req, Instant at, boolean vetoed) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("decisionId", req.decisionId());
    p.put("decision", vetoed ? "REJECTED" : "ALLOW");
    p.put("score", 0);
    p.put("requiredAction", "NONE");
    p.put("vetoApplied", vetoed ? "SIGNATURE_INVALID" : "NONE");
    p.put("ruleSetVersion", ruleSet.version());
    p.put("evaluatedAt", DateTimeFormatter.ISO_INSTANT.format(at));
    // Contexte de la requête, journalisé avec la décision.
    p.put("accountId", req.accountId());
    p.put("msisdn", req.msisdn());
    p.put("type", req.type());
    p.put("amountXaf", req.amountXaf());
    p.put("beneficiaryId", req.beneficiaryId());
    return p;
  }

  private EvaluateResponse fromPayload(Map<String, Object> p, long latencyMs) {
    if (EVENT_NONCE_REUSED.equals(p.get("event"))) {
      throw new NonceReplayedException(); // même decisionId : même réponse (ENF-03)
    }
    return new EvaluateResponse(
        (String) p.get("decision"),
        ((Number) p.get("score")).intValue(),
        (String) p.get("requiredAction"),
        List.of(),
        (String) p.get("vetoApplied"),
        (String) p.get("decisionId"),
        (String) p.get("ruleSetVersion"),
        (String) p.get("evaluatedAt"),
        latencyMs);
  }

  private static long latencyMs(long startedNanos) {
    return (MonotonicTimer.nanos() - startedNanos) / 1_000_000;
  }
}
