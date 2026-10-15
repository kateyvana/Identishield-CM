package cm.identishield.pdp.rules;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Score.Contribution;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * ES-05b : un bénéficiaire jamais vu contribue W.newBeneficiary ; avec un montant ≥ THR_HIGH, il
 * contribue W.newBeneficiaryHigh à la place. THR_HIGH = min(THR_HIGH.absoluteXaf ;
 * THR_HIGH.medianFactor × médiane des sorties sur THR_HIGH.medianWindow) ; l'absolu seul s'il n'y a
 * aucune sortie dans la fenêtre (ISC-TM v1.4 §7).
 */
final class BeneficiaryRule implements Rule {

  // @implements ES-05b

  private final int wNew;
  private final int wNewHigh;
  private final long absolute;
  private final long factor;
  private final Duration medianWindow;

  BeneficiaryRule(RuleSet rs) {
    this.wNew = (int) rs.integer("W.newBeneficiary");
    this.wNewHigh = (int) rs.integer("W.newBeneficiaryHigh");
    this.absolute = rs.integer("THR_HIGH.absoluteXaf");
    this.factor = rs.integer("THR_HIGH.medianFactor");
    this.medianWindow = rs.duration("THR_HIGH.medianWindow");
  }

  @Override
  public Contribution evaluate(Operation op, List<Operation> history) {
    if (!Operation.isNewCounterparty(op.counterpartyId(), history)) {
      return null;
    }
    return high(op, history)
        ? new Contribution("NEW_BENEFICIARY_HIGH", wNewHigh)
        : new Contribution("NEW_BENEFICIARY", wNew);
  }

  /** montant ≥ THR_HIGH, en arithmétique entière (médiane paire = demi-somme, ED-05). */
  private boolean high(Operation op, List<Operation> history) {
    if (op.amountXaf() >= absolute) {
      return true;
    }
    Instant from = op.at().minus(medianWindow);
    long[] amounts =
        history.stream()
            .filter(o -> o.outgoing() && !o.at().isBefore(from) && o.at().isBefore(op.at()))
            .mapToLong(Operation::amountXaf)
            .sorted()
            .toArray();
    int n = amounts.length;
    if (n == 0) {
      return false;
    }
    if (n % 2 == 1) {
      return op.amountXaf() >= factor * amounts[n / 2];
    }
    return 2 * op.amountXaf() >= factor * (amounts[n / 2 - 1] + amounts[n / 2]);
  }
}
