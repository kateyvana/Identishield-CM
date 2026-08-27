package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;
import java.util.Random;

/**
 * Bypass attempt 3 - the Q7 core case: a no-arg {@link Random} seeds itself on the system clock
 * inside the JDK, so this rule is non-deterministic while receiving a perfectly proper instant.
 * No java.time or System call appears in this bytecode: TA-CLOCK-01 as shipped cannot see it.
 */
public final class GraineTemporelle implements Rule {

  @Override
  public String name() {
    return "TRICHE_GRAINE";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return "ALEA-" + new Random().nextLong();
  }
}
