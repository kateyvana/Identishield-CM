package cm.identishield.spike.clock.rules;

import cm.identishield.spike.clock.Parameters;
import cm.identishield.spike.clock.RuleContext;
import java.time.Instant;

/**
 * Family: future deadline, evaluated lazily at read time (Q4 stance under test: no scheduler).
 * EXPIRE once now is strictly after creation + TTL_FREEZE.
 */
public record ExpirationGel(Parameters params) implements Rule {

  @Override
  public String name() {
    return "GEL";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return ctx.freezeCreatedAtOrBefore(now)
        .filter(created -> now.isAfter(created.plus(params.ttlFreeze())))
        .map(created -> "EXPIRE")
        .orElse("-");
  }
}
