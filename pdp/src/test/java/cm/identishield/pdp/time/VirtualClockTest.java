package cm.identishield.pdp.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * @covers EF-18 (le PDP accepte une horloge virtuelle : instant fixé et avancé sans attente).
 */
class VirtualClockTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void startsAtGivenInstantInUtc() {
    VirtualClock clock = new VirtualClock(T0);
    assertEquals(T0, clock.instant());
    assertEquals(T0.toEpochMilli(), clock.millis());
    assertEquals(ZoneOffset.UTC, clock.getZone());
  }

  @Test
  void advanceMovesInstantForwardWithoutRealWait() {
    VirtualClock clock = new VirtualClock(T0);
    clock.advance(Duration.ofHours(73));
    assertEquals(T0.plus(Duration.ofHours(73)), clock.instant());
  }

  @Test
  void setInstantJumpsToArbitraryInstant() {
    VirtualClock clock = new VirtualClock(T0);
    Instant jump = Instant.parse("2026-02-05T16:38:50Z");
    clock.setInstant(jump);
    assertEquals(jump, clock.instant());
  }

  @Test
  void withZoneKeepsSameInstant() {
    VirtualClock clock = new VirtualClock(T0);
    clock.advance(Duration.ofMinutes(90));
    assertEquals(clock.instant(), clock.withZone(ZoneOffset.ofHours(1)).instant());
  }
}
