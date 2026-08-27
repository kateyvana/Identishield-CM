package cm.identishield.spike.clock.rules;

import cm.identishield.spike.clock.Parameters;
import cm.identishield.spike.clock.RuleContext;
import java.time.Instant;

/**
 * Family: count over a sliding window. DEPASSEMENT when strictly more than VEL outflows fall in
 * (now - VEL_WINDOW, now]. Pure recount at every evaluation - no mutable buffer, so the verdict
 * depends only on (history, now), never on how the clock travelled to now.
 */
public record Velocite(Parameters params) implements Rule {

  @Override
  public String name() {
    return "VEL";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    long count = ctx.outflowCount(now.minus(params.velWindow()), now);
    return count > params.vel() ? "DEPASSEMENT" : "-";
  }
}
