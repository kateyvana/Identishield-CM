package cm.identishield.pdp.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * @covers EF-18 (le PDP accepte une horloge injectable : reelle en SYSTEM, virtuelle en VIRTUAL).
 */
class TimeConfigTest {

  private final TimeConfig config = new TimeConfig();

  @Test
  void virtualModeYieldsVirtualClockAtGivenStart() {
    Instant start = Instant.parse("2026-02-05T16:38:50Z");
    Clock clock = config.clock(new ClockProperties(ClockProperties.Mode.VIRTUAL, start));
    VirtualClock virtual = assertInstanceOf(VirtualClock.class, clock);
    assertEquals(start, virtual.instant());
  }

  @Test
  void systemModeYieldsRealClockNotVirtual() {
    Clock clock = config.clock(new ClockProperties(ClockProperties.Mode.SYSTEM, null));
    assertFalse(
        clock instanceof VirtualClock, "SYSTEM doit fournir l'horloge reelle, pas virtuelle");
  }
}
