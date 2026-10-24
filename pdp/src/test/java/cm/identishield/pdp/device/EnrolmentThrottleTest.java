package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * ADR-030 : délai LOCK_BASE doublé à chaque échec au-delà de REENROL_ATTEMPTS, plafonné par
 * LOCK_MAX ; jamais de débordement. Le comptage en base est prouvé par DeviceEnrolmentDbTest.
 *
 * <p>@covers ES-21
 */
class EnrolmentThrottleTest {

  private static final Duration BASE = Duration.ofSeconds(30);
  private static final Duration MAX = Duration.ofHours(24);

  @Test
  void theDelayDoublesFromTheNthFailure_andIsCapped() {
    assertEquals(BASE, EnrolmentThrottle.lockAfter(2, 2, BASE, MAX), "au N-ième : LOCK_BASE");
    assertEquals(BASE.multipliedBy(2), EnrolmentThrottle.lockAfter(3, 2, BASE, MAX));
    assertEquals(BASE.multipliedBy(4), EnrolmentThrottle.lockAfter(4, 2, BASE, MAX));
    assertEquals(MAX, EnrolmentThrottle.lockAfter(20, 2, BASE, MAX), "plafonné");
    assertEquals(
        MAX, EnrolmentThrottle.lockAfter(2_000_000, 2, BASE, MAX), "jamais de débordement");
  }
}
