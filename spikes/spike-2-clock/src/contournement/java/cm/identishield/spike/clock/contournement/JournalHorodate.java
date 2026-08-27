package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Bypass attempt 5 - timestamped logging: the framework stamps the record with the system clock
 * inside java.util.logging. The visible call targets Logger only; the time read happens in the
 * framework's bytecode, out of the rule's own call sites.
 */
public final class JournalHorodate implements Rule {

  private static final Logger LOG = Logger.getLogger(JournalHorodate.class.getName());

  @Override
  public String name() {
    return "TRICHE_JOURNAL";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    LOG.info("evaluation");
    return "JOURNALISE";
  }
}
