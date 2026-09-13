package cm.identishield.core.hlr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Test temporel de la fenêtre CAR_IMSI (72 h) sur l'horloge VIRTUELLE, sans attente réelle : après
 * une réattribution, on place l'horloge à t+71 h (dans la fenêtre) et t+73 h (hors fenêtre).
 *
 * @covers EF-08
 */
class CarImsiWindowTest {

  private static final Duration CAR_IMSI = Duration.ofHours(72);
  private static final Instant CHANGED_AT = Instant.parse("2026-09-13T00:00:00Z");

  @Test
  void at71hStillWithinWindow() {
    VirtualClock clock = new VirtualClock(CHANGED_AT);
    clock.advance(Duration.ofHours(71));
    assertTrue(CarImsiWindow.isWithin(CHANGED_AT, clock.instant(), CAR_IMSI));
  }

  @Test
  void at73hOutsideWindow() {
    VirtualClock clock = new VirtualClock(CHANGED_AT);
    clock.advance(Duration.ofHours(73));
    assertFalse(CarImsiWindow.isWithin(CHANGED_AT, clock.instant(), CAR_IMSI));
  }

  @Test
  void atExactly72hWindowIsClosed() {
    VirtualClock clock = new VirtualClock(CHANGED_AT);
    clock.advance(CAR_IMSI);
    assertFalse(CarImsiWindow.isWithin(CHANGED_AT, clock.instant(), CAR_IMSI));
  }
}
