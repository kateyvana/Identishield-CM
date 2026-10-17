package cm.identishield.pdp.trust;

import cm.identishield.pdp.rules.Score.Decision;
import org.springframework.stereotype.Component;

/**
 * Application d'une décision du PDP à l'état du compte (carte A3, ES-04 : toute violation produit
 * FREEZE). Le branchement dans /evaluate, après les vetos et le moteur de règles, est la carte A4.
 */
@Component
public class DecisionEnforcer {

  // @implements EF-13

  /** Effet d'une décision sur l'état du compte. */
  public enum Effect {
    NONE,
    ACCOUNT_FROZEN,
    ALREADY_FROZEN
  }

  private final TrustStateMachine trust;

  public DecisionEnforcer(TrustStateMachine trust) {
    this.trust = trust;
  }

  /**
   * FREEZE : le compte passe à FROZEN par la machine A1, pour TTL_FREEZE ; une période CAR_IMSI en
   * cours est conservée (A1-2), ce gel par décision expire (A1-3). Un compte déjà gelé le reste,
   * sans nouvelle transition ni TTL prolongé. ALLOW, STEP_UP, REJECTED : l'état ne change pas.
   */
  public Effect apply(String accountId, String decisionId, Decision decision) {
    if (decision != Decision.FREEZE) {
      return Effect.NONE;
    }
    try {
      trust.frozenByDecision(accountId, decisionId);
      return Effect.ACCOUNT_FROZEN;
    } catch (TrustStateMachine.Refused alreadyFrozen) {
      return Effect.ALREADY_FROZEN;
    }
  }
}
