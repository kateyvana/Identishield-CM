package cm.identishield.spike.clock.rules;

import cm.identishield.spike.clock.RuleContext;
import java.time.Instant;

/**
 * Q2 candidate form: the rule receives the evaluation instant as a parameter and never holds a
 * clock. The clock is injected once, at the evaluator boundary. Verdict is the family token, or
 * "-" when the rule has nothing to say.
 */
public interface Rule {
  String name();

  String verdict(RuleContext ctx, Instant now);
}
