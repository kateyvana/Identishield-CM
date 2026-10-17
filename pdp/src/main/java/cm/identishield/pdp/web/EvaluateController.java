package cm.identishield.pdp.web;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.audit.AuthorizedHistory;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Operation;
import cm.identishield.pdp.rules.RuleEngine;
import cm.identishield.pdp.rules.RuleExplanations;
import cm.identishield.pdp.rules.Score;
import cm.identishield.pdp.rules.Score.Decision;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.signature.SignatureVerifier.SignedTransaction;
import cm.identishield.pdp.signature.SignatureVerifier.Verdict;
import cm.identishield.pdp.time.MonotonicTimer;
import cm.identishield.pdp.trust.DecisionEnforcer;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.ProbationVeto.Violation;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
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
 * POST /evaluate (EF-10, EF-11, ENF-03). Chaîne de décision (carte A4, V1) : idempotence → veto de
 * signature → état du compte (compte gelé, veto de PROBATION, solde dépassé) → moteur de règles.
 * L'instant de l'opération est lu UNE fois à l'entrée (SAD §11) ; l'historique des règles est relu
 * dans le journal du PDP, décisions ALLOW seulement (V2, V3). Chaque décision porte la version du
 * jeu de règles (EF-11). Le gel du compte ne suit que la première écriture (V6). Limite du mois 4 :
 * une opération APP non signée n'est pas refusée (ES-02 imposée avec la route mobile signée).
 * Idempotence par decisionId : un rejeu renvoie la même décision et n'écrit pas de seconde entrée
 * d'audit. Exactement une entrée d'audit chaînée par décision (le trigger en base chaîne in-lock ;
 * vérification en T2-06).
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
// @implements ES-01b
// @implements ES-04
// @implements ES-05f
@RestController
public class EvaluateController {

  static final String PROOF_SIGNATURE = "SIGNATURE";
  static final String EVENT_NONCE_REUSED = "NONCE_REUSED";
  static final String FREEZE = "FREEZE";

  /** Hypothèse explicite : CASH_IN est la seule entrée, tous les autres types sont des sorties. */
  static final String CASH_IN = "CASH_IN";

  private final AuditLog auditLog;
  private final Clock clock;
  private final RuleSet ruleSet;
  private final SignatureVerifier signatureVerifier;
  private final TrustStateMachine trust;
  private final AuthorizedHistory history;
  private final RuleEngine engine;
  private final ProbationVeto probationVeto;
  private final DecisionEnforcer enforcer;

  public EvaluateController(
      AuditLog auditLog,
      Clock clock,
      RuleSet ruleSet,
      SignatureVerifier signatureVerifier,
      TrustStateMachine trust,
      AuthorizedHistory history,
      RuleEngine engine,
      ProbationVeto probationVeto,
      DecisionEnforcer enforcer) {
    this.auditLog = auditLog;
    this.clock = clock;
    this.ruleSet = ruleSet;
    this.signatureVerifier = signatureVerifier;
    this.trust = trust;
    this.history = history;
    this.engine = engine;
    this.probationVeto = probationVeto;
    this.enforcer = enforcer;
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
    Map<String, Object> payload =
        verdict == Verdict.INVALID
            ? payload(req, now, "REJECTED", 0, "NONE", "SIGNATURE_INVALID", List.of())
            : decide(req, now);
    try {
      auditLog.append(payload, now);
    } catch (DuplicateKeyException concurrentReplay) {
      // Deux appels simultanés du même decisionId : renvoyer la décision gagnante.
      return fromPayload(
          auditLog.findByDecisionId(req.decisionId()).orElseThrow(), latencyMs(started));
    }
    // V6 : l'effet d'une décision (gel du compte) ne suit que la PREMIÈRE écriture de ce
    // decisionId. Un rejeu repart par l'idempotence ci-dessus et n'arrive jamais ici.
    if (FREEZE.equals(payload.get("decision"))) {
      enforcer.apply(req.accountId(), req.decisionId(), Decision.FREEZE);
    }
    return fromPayload(payload, latencyMs(started));
  }

  /**
   * État du compte, puis règles (V1). Le compte apprend d'abord l'IMSI courant et la date du
   * dernier changement d'IMSI (Q3, Q4). Pour une sortie, ensuite : compte gelé (ACCOUNT_FROZEN),
   * veto de PROBATION (ES-04), solde dépassé (INSUFFICIENT_FUNDS) ; enfin le score (ES-05a à
   * ES-05f). Un veto ne passe jamais par le moteur de règles (CLAUDE.md §3) : score 0, aucune
   * règle. Une entrée (CASH_IN) passe même sur un compte gelé : le gel ne suspend que les sorties
   * (SAD §7.5).
   */
  private Map<String, Object> decide(EvaluateRequest req, Instant now) {
    Trust t =
        trust.observed(
            req.accountId(), req.currentImsi(), instant(req.imsiChangedAt()), req.balanceXaf());
    Operation op =
        new Operation(req.beneficiaryId(), req.amountXaf(), !CASH_IN.equals(req.type()), now);
    if (op.outgoing() && t.state() == State.FROZEN) {
      return payload(req, now, "REJECTED", 0, "NONE", "ACCOUNT_FROZEN", List.of());
    }
    List<Operation> authorized = history.of(req.accountId());
    if (op.outgoing() && t.state() == State.PROBATION) {
      Optional<Violation> v =
          probationVeto.check(op, authorized, t.probationEnteredAt(), t.probationEntryBalanceXaf());
      if (v.isPresent()) {
        Map<String, Object> p =
            payload(req, now, FREEZE, 0, "NONE", "PROBATION_VIOLATION", List.of());
        p.put("probationViolation", v.get().name());
        return p;
      }
    }
    // Q5 : une sortie au-delà du solde est refusée ici, donc jamais autorisée, donc jamais dans
    // l'historique : sans ce refus, un ALLOW suivi d'un refus du cœur « blanchirait » le
    // bénéficiaire.
    if (op.outgoing() && req.amountXaf() > req.balanceXaf()) {
      return payload(req, now, "REJECTED", 0, "NONE", "INSUFFICIENT_FUNDS", List.of());
    }
    Score score = engine.score(op, authorized);
    List<Map<String, Object>> rules =
        score.contributions().stream()
            .map(
                c -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("ruleId", c.ruleId());
                  m.put("contribution", c.points());
                  m.put("explanation", RuleExplanations.of(c.ruleId()));
                  return m;
                })
            .toList();
    // V5 : un STEP_UP exige un report (DEFER) ; le CŒUR le résout selon le canal (carte A3).
    String requiredAction = score.decision() == Decision.STEP_UP ? "DEFER" : "NONE";
    return payload(req, now, score.decision().name(), score.value(), requiredAction, "NONE", rules);
  }

  private static Instant instant(String iso) {
    return iso == null ? null : Instant.parse(iso);
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
   * Décision et contexte de la requête, pour l'audit (EF-11) : l'entrée porte de quoi reconstruire
   * l'historique (accountId, type, amountXaf, beneficiaryId, evaluatedAt, decision).
   */
  private Map<String, Object> payload(
      EvaluateRequest req,
      Instant at,
      String decision,
      int score,
      String requiredAction,
      String vetoApplied,
      List<Map<String, Object>> triggeredRules) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("decisionId", req.decisionId());
    p.put("decision", decision);
    p.put("score", score);
    p.put("requiredAction", requiredAction);
    p.put("vetoApplied", vetoApplied);
    p.put("triggeredRules", triggeredRules);
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
        triggeredRules(p.get("triggeredRules")),
        (String) p.get("vetoApplied"),
        (String) p.get("decisionId"),
        (String) p.get("ruleSetVersion"),
        (String) p.get("evaluatedAt"),
        latencyMs);
  }

  @SuppressWarnings("unchecked")
  private static List<TriggeredRule> triggeredRules(Object stored) {
    if (!(stored instanceof List<?> list)) {
      return List.of(); // entrées antérieures à la carte A4
    }
    return list.stream()
        .map(o -> (Map<String, Object>) o)
        .map(
            m ->
                new TriggeredRule(
                    (String) m.get("ruleId"),
                    ((Number) m.get("contribution")).intValue(),
                    (String) m.get("explanation")))
        .toList();
  }

  private static long latencyMs(long startedNanos) {
    return (MonotonicTimer.nanos() - startedNanos) / 1_000_000;
  }
}
