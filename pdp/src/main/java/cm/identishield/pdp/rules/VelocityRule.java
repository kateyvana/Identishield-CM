package cm.identishield.pdp.rules;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Score.Contribution;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ES-05c : plus de VEL.count sorties sur VEL.window, OU (vers un bénéficiaire nouveau) plus de
 * VEL.newBeneficiaries bénéficiaires nouveaux distincts sur VEL.beneficiaryWindow, l'opération
 * comprise. Fenêtres ouvertes à gauche : ]t - fenêtre ; t].
 */
final class VelocityRule implements Rule {

  // @implements ES-05c

  private final int wVel;
  private final int count;
  private final Duration window;
  private final int newBeneficiaries;
  private final Duration beneficiaryWindow;

  VelocityRule(RuleSet rs) {
    this.wVel = (int) rs.integer("W.vel");
    this.count = (int) rs.integer("VEL.count");
    this.window = rs.duration("VEL.window");
    this.newBeneficiaries = (int) rs.integer("VEL.newBeneficiaries");
    this.beneficiaryWindow = rs.duration("VEL.beneficiaryWindow");
  }

  @Override
  public Contribution evaluate(Operation op, List<Operation> history) {
    return tooManyOutflows(op, history) || tooManyNewBeneficiaries(op, history)
        ? new Contribution("VELOCITY", wVel)
        : null;
  }

  private boolean tooManyOutflows(Operation op, List<Operation> history) {
    Instant from = op.at().minus(window);
    long recent = history.stream().filter(o -> o.outgoing() && o.at().isAfter(from)).count();
    return recent + 1 > count;
  }

  private boolean tooManyNewBeneficiaries(Operation op, List<Operation> history) {
    if (!Operation.isNewCounterparty(op.counterpartyId(), history)) {
      return false;
    }
    Instant from = op.at().minus(beneficiaryWindow);
    Set<String> seen = new HashSet<>();
    Set<String> recentNew = new HashSet<>();
    for (Operation o : history) {
      boolean firstSeen = seen.add(o.counterpartyId());
      if (firstSeen && o.outgoing() && o.at().isAfter(from)) {
        recentNew.add(o.counterpartyId());
      }
    }
    recentNew.add(op.counterpartyId());
    return recentNew.size() > newBeneficiaries;
  }
}
