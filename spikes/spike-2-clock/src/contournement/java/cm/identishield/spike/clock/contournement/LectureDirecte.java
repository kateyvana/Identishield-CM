package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;

/** Bypass attempt 1 - direct read: ignores the received instant, calls Instant.now(). */
public final class LectureDirecte implements Rule {

  @Override
  public String name() {
    return "TRICHE_DIRECTE";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return Instant.now().isAfter(now) ? "APRES" : "AVANT";
  }
}
