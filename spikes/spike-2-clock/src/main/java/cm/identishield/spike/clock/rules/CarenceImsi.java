package cm.identishield.spike.clock.rules;

import cm.identishield.spike.clock.Parameters;
import cm.identishield.spike.clock.RuleContext;
import java.time.Duration;
import java.time.Instant;

/** Family: window since a past event. EN_CARENCE while now - imsiChange < CAR_IMSI (strict). */
public record CarenceImsi(Parameters params) implements Rule {

  @Override
  public String name() {
    return "CARENCE";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return ctx.lastImsiChangeAtOrBefore(now)
        .filter(change -> Duration.between(change, now).compareTo(params.carImsi()) < 0)
        .map(change -> "EN_CARENCE")
        .orElse("-");
  }
}
