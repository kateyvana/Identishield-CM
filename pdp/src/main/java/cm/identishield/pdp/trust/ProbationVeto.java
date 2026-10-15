package cm.identishield.pdp.trust;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Operation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Veto de PROBATION (ES-04, ADR-016 §1 étape 1) : en PROBATION, une sortie vers un bénéficiaire
 * nouveau, ou dont le montant porte le cumul des sorties depuis l'entrée, sur la fenêtre glissante
 * CAP_PROB.window, au-delà de min(CAP_PROB.dailyXaf ; CAP_PROB.balancePct % du solde à l'entrée en
 * PROBATION), est gelée. Composant distinct du moteur de score : un veto ne passe jamais par le
 * RuleEngine (CLAUDE.md §3). L'appelant (carte A4) ne l'invoque que si le compte est en PROBATION
 * (machine à états, carte A1).
 */
@Component
public class ProbationVeto {

  // @implements ES-04

  public enum Violation {
    NEW_BENEFICIARY,
    CAP_PROB
  }

  private final long dailyXaf;
  private final long balancePct;
  private final Duration window;

  public ProbationVeto(RuleSet rs) {
    if (!"PROBATION_ENTRY".equals(rs.text("CAP_PROB.balanceBase"))) {
      throw new IllegalStateException(
          "CAP_PROB.balanceBase non pris en charge : " + rs.text("CAP_PROB.balanceBase"));
    }
    this.dailyXaf = rs.integer("CAP_PROB.dailyXaf");
    this.balancePct = rs.integer("CAP_PROB.balancePct");
    this.window = rs.duration("CAP_PROB.window");
  }

  /**
   * @param history opérations exécutées du compte, antérieures à {@code op}
   * @param probationEnteredAt instant d'entrée en PROBATION
   * @param entryBalanceXaf solde du compte à cet instant (CAP_PROB.balanceBase = PROBATION_ENTRY)
   */
  public Optional<Violation> check(
      Operation op, List<Operation> history, Instant probationEnteredAt, long entryBalanceXaf) {
    if (!op.outgoing()) {
      return Optional.empty();
    }
    if (Operation.isNewCounterparty(op.counterpartyId(), history)) {
      return Optional.of(Violation.NEW_BENEFICIARY);
    }
    Instant from = op.at().minus(window);
    long spent =
        history.stream()
            .filter(o -> o.outgoing() && o.at().isAfter(from))
            .filter(o -> !o.at().isBefore(probationEnteredAt))
            .mapToLong(Operation::amountXaf)
            .sum();
    long limit = Math.min(dailyXaf, entryBalanceXaf * balancePct / 100);
    return spent + op.amountXaf() > limit ? Optional.of(Violation.CAP_PROB) : Optional.empty();
  }
}
