package cm.identishield.core.transfer;

import java.time.Instant;

/**
 * Issue d'une opération. Le decisionId lie la transaction à son entrée d'audit. Pour une opération
 * différée (STEP_UP résolu par DEFER), {@code detail} porte l'échéance d'exécution (instant UTC au
 * format ISO).
 */
public record TransferResult(
    State state, String decisionId, String detail, String vetoApplied, String decision) {

  /**
   * Aucun veto : valeur du PDP pour une décision rendue par le moteur ou une opération sans
   * /evaluate.
   */
  public static final String NO_VETO = "NONE";

  /** Sans veto ni décision du PDP (aucun /evaluate rendu : dégradé, sans couche, refus amont). */
  public TransferResult(State state, String decisionId, String detail) {
    this(state, decisionId, detail, NO_VETO, null);
  }

  /** Veto connu, décision du PDP non précisée (REJECTED). */
  public TransferResult(State state, String decisionId, String detail, String vetoApplied) {
    this(state, decisionId, detail, vetoApplied, null);
  }

  /**
   * La décision du PDP telle que rendue (ALLOW, STEP_UP, FREEZE, REJECTED) posée sur cette issue ;
   * la route mobile en fait DECISION_FROZEN / DECISION_REJECTED sans lire {@code detail}.
   */
  public TransferResult withDecision(String pdpDecision) {
    return new TransferResult(state, decisionId, detail, vetoApplied, pdpDecision);
  }

  public enum State {
    EXECUTED,
    EXECUTED_DEGRADED,
    REJECTED,
    /** STEP_UP résolu par CANCEL (USSD, ADR-016 §3) ou opération différée annulée. */
    CANCELLED,
    /** STEP_UP résolu par DEFER (application, ADR-016-A) : exécution à l'échéance, annulable. */
    DEFERRED
  }

  public static TransferResult executed(String decisionId) {
    return new TransferResult(State.EXECUTED, decisionId, null);
  }

  public static TransferResult rejected(String decisionId, String detail) {
    return new TransferResult(State.REJECTED, decisionId, detail);
  }

  /**
   * REJECTED décidé par le PDP : {@code vetoApplied} est la chaîne du PDP (ACCOUNT_FROZEN,
   * SIGNATURE_INVALID, PROBATION_VIOLATION, …, ou NONE quand c'est le score), jamais réinterprétée
   * par le cœur ; la route mobile en fait sa réponse (403 ACCOUNT_FROZEN / DECISION_REJECTED).
   */
  public static TransferResult rejected(String decisionId, String detail, String vetoApplied) {
    return new TransferResult(
        State.REJECTED, decisionId, detail, vetoApplied == null ? NO_VETO : vetoApplied);
  }

  public static TransferResult cancelled(String decisionId, String detail) {
    return new TransferResult(State.CANCELLED, decisionId, detail);
  }

  public static TransferResult deferred(String decisionId, Instant executeAt) {
    return new TransferResult(State.DEFERRED, decisionId, executeAt.toString());
  }
}
