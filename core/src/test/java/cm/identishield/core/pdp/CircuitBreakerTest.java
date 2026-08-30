package cm.identishield.core.pdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * @covers ES-11 (disjoncteur du client PDP : ouverture/refroidissement/essai).
 */
class CircuitBreakerTest {

  /** Horloge mutable de test (les tests ne sont pas soumis a TA-CLOCK-01). */
  private static final class MutableClock extends Clock {
    private long millis;

    @Override
    public long millis() {
      return millis;
    }

    void advance(long ms) {
      millis += ms;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Test
  void opensAfterThresholdConsecutiveFailures() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(3, 5000, clock);

    assertTrue(cb.allowRequest());
    cb.recordFailure();
    cb.recordFailure();
    assertEquals(CircuitBreaker.State.CLOSED, cb.state()); // 2 < 3
    cb.recordFailure(); // 3e echec -> ouvre
    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertFalse(cb.allowRequest());
  }

  @Test
  void halfOpensAfterCooldownThenClosesOnSuccess() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(1, 5000, clock);

    cb.recordFailure(); // ouvre (seuil 1)
    assertFalse(cb.allowRequest());

    clock.advance(5000); // refroidissement ecoule
    assertTrue(cb.allowRequest()); // laisse passer un essai
    assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());

    cb.recordSuccess(); // essai reussi -> referme
    assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    assertTrue(cb.allowRequest());
  }

  @Test
  void reopensWhenHalfOpenTrialFails() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(1, 5000, clock);

    cb.recordFailure();
    clock.advance(5000);
    assertTrue(cb.allowRequest()); // HALF_OPEN
    cb.recordFailure(); // l'essai echoue -> rouvre
    assertEquals(CircuitBreaker.State.OPEN, cb.state());
    assertFalse(cb.allowRequest());
  }

  @Test
  void blocksDuringCooldownWindow() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(1, 5000, clock);

    cb.recordFailure();
    clock.advance(4999); // avant la fin du refroidissement
    assertFalse(cb.allowRequest());
  }
}
