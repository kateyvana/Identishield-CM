package cm.identishield.core.pdp;

/**
 * Issue d'un appel /evaluate côté cœur. {@code available=false} => le PDP n'a pas rendu de décision
 * (indisponible / disjoncteur ouvert) : le cœur applique alors sa politique fail-closed (ES-11).
 */
public record EvaluateOutcome(
    boolean available, String decision, String decisionId, String vetoApplied) {

  /** Veto appliqué par le PDP (NONE, SIGNATURE_INVALID, ACCOUNT_FROZEN, …), tel que renvoyé. */
  public static final String NO_VETO = "NONE";

  public static EvaluateOutcome unavailable() {
    return new EvaluateOutcome(false, null, null, NO_VETO);
  }

  public static EvaluateOutcome of(String decision, String decisionId) {
    return of(decision, decisionId, NO_VETO);
  }

  public static EvaluateOutcome of(String decision, String decisionId, String vetoApplied) {
    return new EvaluateOutcome(
        true, decision, decisionId, vetoApplied == null ? NO_VETO : vetoApplied);
  }

  public boolean isAllow() {
    return available && "ALLOW".equals(decision);
  }

  /** STEP_UP : le cœur le résout selon le canal (ES-06, STEP_UP_POLICY). */
  public boolean isStepUp() {
    return available && "STEP_UP".equals(decision);
  }
}
