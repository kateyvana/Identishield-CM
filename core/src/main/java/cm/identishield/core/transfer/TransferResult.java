package cm.identishield.core.transfer;

import java.time.Instant;

/**
 * Issue d'une opération. Le decisionId lie la transaction à son entrée d'audit. Pour une opération
 * différée (STEP_UP résolu par DEFER), {@code detail} porte l'échéance d'exécution (instant UTC au
 * format ISO).
 */
public record TransferResult(State state, String decisionId, String detail) {

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

  public static TransferResult cancelled(String decisionId, String detail) {
    return new TransferResult(State.CANCELLED, decisionId, detail);
  }

  public static TransferResult deferred(String decisionId, Instant executeAt) {
    return new TransferResult(State.DEFERRED, decisionId, executeAt.toString());
  }
}
