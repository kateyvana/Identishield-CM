package cm.identishield.spike.clock.contournement;

import cm.identishield.spike.clock.RuleContext;
import cm.identishield.spike.clock.rules.Rule;
import java.time.Instant;
import java.util.UUID;

/**
 * Bypass attempt 6 - UUID.randomUUID(): entropy comes from a SecureRandom, so the verdict is
 * non-deterministic whatever the injected instant; whether that entropy is clock-derived on this
 * JVM is measured by the test, not assumed.
 */
public final class EntropieUuid implements Rule {

  @Override
  public String name() {
    return "TRICHE_UUID";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    return "UUID-" + UUID.randomUUID();
  }
}
