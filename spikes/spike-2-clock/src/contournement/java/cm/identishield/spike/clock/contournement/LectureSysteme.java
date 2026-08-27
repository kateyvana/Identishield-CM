package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;

/** Bypass attempt 2 - low-level read: System.currentTimeMillis(). */
public final class LectureSysteme implements Rule {

  @Override
  public String name() {
    return "TRICHE_SYSTEME";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return System.currentTimeMillis() % 2 == 0 ? "PAIR" : "IMPAIR";
  }
}
