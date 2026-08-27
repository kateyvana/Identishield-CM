package cm.identishield.spike.clock.rules;

import cm.identishield.spike.clock.Parameters;
import cm.identishield.spike.clock.RuleContext;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Family: recurring daily window. Q5 stance under test: the clock hands out a zone-less Instant;
 * the UTC conversion belongs to this rule, because NIGHT is defined in server UTC (ED-04). The
 * window wraps midnight: [start, 24h) union [0h, end).
 */
public record NuitOuvree(Parameters params) implements Rule {

  @Override
  public String name() {
    return "NUIT";
  }

  @Override
  public String verdict(RuleContext ctx, Instant now) {
    LocalTime utc = now.atOffset(ZoneOffset.UTC).toLocalTime();
    LocalTime start = params.nightStart();
    LocalTime end = params.nightEnd();
    boolean inside =
        start.isAfter(end)
            ? !utc.isBefore(start) || utc.isBefore(end)
            : !utc.isBefore(start) && utc.isBefore(end);
    return inside ? "NUIT" : "-";
  }
}
