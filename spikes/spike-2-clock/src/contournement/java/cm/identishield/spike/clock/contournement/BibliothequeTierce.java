package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;
import org.apache.commons.lang3.time.StopWatch;

/**
 * Bypass attempt 4 - third-party wrapper: commons-lang3 StopWatch reads System.nanoTime inside
 * its own bytecode. The call visible here targets org.apache.commons classes only, which
 * TA-CLOCK-01's owner-based predicate does not inspect.
 */
public final class BibliothequeTierce implements Rule {

  @Override
  public String name() {
    return "TRICHE_TIERCE";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    StopWatch watch = StopWatch.createStarted();
    watch.stop();
    return "TIERCE-" + watch.getTime();
  }
}
