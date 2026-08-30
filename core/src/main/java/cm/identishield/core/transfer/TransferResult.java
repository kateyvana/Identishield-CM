package cm.identishield.core.transfer;

/** Issue d'un transfert P2P. Le decisionId lie la transaction à son entrée d'audit. */
public record TransferResult(State state, String decisionId, String detail) {

  public enum State {
    EXECUTED,
    EXECUTED_DEGRADED,
    REJECTED
  }

  public static TransferResult executed(String decisionId) {
    return new TransferResult(State.EXECUTED, decisionId, null);
  }

  public static TransferResult rejected(String decisionId, String detail) {
    return new TransferResult(State.REJECTED, decisionId, detail);
  }
}
