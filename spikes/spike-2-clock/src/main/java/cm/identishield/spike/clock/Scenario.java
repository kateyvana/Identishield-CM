package cm.identishield.spike.clock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded generator of a six-virtual-month event sequence crossing the four time-dependency
 * families: enrolment, one IMSI change, outflow series (including bursts inside a 10-minute
 * window), one freeze creation. Pure function of (seed, start): no clock involved, so the sequence
 * is identical on every run with the same inputs.
 *
 * <p>Sandbox rule: the fictitious account is in the reserved MSISDN range 699000000-699009999.
 */
public final class Scenario {

  public static final String ACCOUNT = "699000123";
  private static final Duration SIX_MONTHS = Duration.ofDays(182);

  private Scenario() {}

  public static List<ScenarioEvent> generate(long seed, Instant start) {
    Random random = new Random(seed);
    List<ScenarioEvent> events = new ArrayList<>();
    long seq = 0;

    events.add(new ScenarioEvent(seq++, start, ScenarioEvent.Type.ENROLMENT, 0));

    // One IMSI change somewhere in the second virtual month.
    Instant imsiChange = start.plus(Duration.ofDays(35)).plusSeconds(random.nextInt(86_400));
    events.add(new ScenarioEvent(seq++, imsiChange, ScenarioEvent.Type.IMSI_CHANGE, 0));

    // One freeze creation in the fourth virtual month.
    Instant freeze = start.plus(Duration.ofDays(100)).plusSeconds(random.nextInt(86_400));
    events.add(new ScenarioEvent(seq++, freeze, ScenarioEvent.Type.FREEZE_CREATED, 0));

    // Regular outflows spread over six months: random gaps of 1 h to 3 days.
    Instant cursor = start.plusSeconds(3_600 + random.nextInt(86_400));
    Instant end = start.plus(SIX_MONTHS);
    while (cursor.isBefore(end)) {
      long amount = 500L * (1 + random.nextInt(200));
      events.add(new ScenarioEvent(seq++, cursor, ScenarioEvent.Type.OUTFLOW, amount));
      // Occasionally a burst: 4 to 6 extra outflows within ten minutes.
      if (random.nextInt(12) == 0) {
        int burst = 4 + random.nextInt(3);
        Instant burstCursor = cursor;
        for (int i = 0; i < burst && burstCursor.isBefore(end); i++) {
          burstCursor = burstCursor.plusSeconds(30 + random.nextInt(90));
          long burstAmount = 500L * (1 + random.nextInt(200));
          events.add(new ScenarioEvent(seq++, burstCursor, ScenarioEvent.Type.OUTFLOW, burstAmount));
        }
      }
      cursor = cursor.plusSeconds(3_600 + random.nextInt(3 * 86_400));
    }

    events.sort(
        java.util.Comparator.comparing(ScenarioEvent::at).thenComparingLong(ScenarioEvent::seq));
    return events;
  }
}
