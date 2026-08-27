package cm.identishield.spike.clock;

import cm.identishield.spike.clock.rules.CarenceImsi;
import cm.identishield.spike.clock.rules.ExpirationGel;
import cm.identishield.spike.clock.rules.NuitOuvree;
import cm.identishield.spike.clock.rules.Rule;
import cm.identishield.spike.clock.rules.Velocite;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Minimal evaluator (spec §6, composant 3). The only holder of the injected {@link Clock}: it
 * reads the instant exactly once per evaluation and hands that snapshot to every rule, so all four
 * verdicts of one decision line share the same now.
 */
public final class Evaluator {

  private final Clock clock;
  private final List<Rule> rules;

  public Evaluator(Clock clock, Parameters params) {
    this.clock = clock;
    this.rules =
        List.of(
            new CarenceImsi(params),
            new NuitOuvree(params),
            new Velocite(params),
            new ExpirationGel(params));
  }

  /** One decision line: the instant read back from the clock, then each rule's verdict. */
  public String evaluate(RuleContext ctx) {
    Instant now = clock.instant();
    StringBuilder line = new StringBuilder("clock=").append(now);
    for (Rule rule : rules) {
      line.append('|').append(rule.name()).append('=').append(rule.verdict(ctx, now));
    }
    return line.toString();
  }
}
