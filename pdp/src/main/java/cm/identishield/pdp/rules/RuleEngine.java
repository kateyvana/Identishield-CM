package cm.identishield.pdp.rules;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.rules.Score.Contribution;
import cm.identishield.pdp.rules.Score.Decision;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Moteur de règles (ISC-SAD §5, ADR-016 §1 étapes 2 et 3) : chaque règle apporte sa contribution,
 * le score est leur somme bornée à SCORE.max, la décision suit les bandes SCORE.stepUp et
 * SCORE.freeze. Seules les sorties sont notées. Les vetos (signature, état du compte) ne passent
 * jamais par ce moteur (CLAUDE.md §3) ; leur ordre est la carte A4.
 */
@Component
public class RuleEngine {

  // @implements ES-05a
  // @implements ES-05f

  private static final String BOUNDED_SUM = "bounded_sum";

  private final List<Rule> rules;
  private final int max;
  private final int stepUp;
  private final int freeze;

  public RuleEngine(RuleSet rs) {
    if (!BOUNDED_SUM.equals(rs.text("SCORE.aggregation"))) {
      throw new IllegalStateException(
          "SCORE.aggregation non prise en charge : " + rs.text("SCORE.aggregation"));
    }
    this.max = (int) rs.integer("SCORE.max");
    this.stepUp = (int) rs.integer("SCORE.stepUp");
    this.freeze = (int) rs.integer("SCORE.freeze");
    this.rules = List.of(new BeneficiaryRule(rs), new VelocityRule(rs), new NightRule(rs));
  }

  public Score score(Operation op, List<Operation> history) {
    if (!op.outgoing()) {
      return new Score(0, Decision.ALLOW, List.of());
    }
    List<Contribution> contributions =
        rules.stream().map(r -> r.evaluate(op, history)).filter(Objects::nonNull).toList();
    int value = Math.min(max, contributions.stream().mapToInt(Contribution::points).sum());
    Decision d =
        value >= freeze ? Decision.FREEZE : value >= stepUp ? Decision.STEP_UP : Decision.ALLOW;
    return new Score(value, d, contributions);
  }
}
