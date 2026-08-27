package cm.identishield.spike.clock.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * C-08 / ADR-019 candidate: a mutable {@link Clock} whose instant is set or advanced by the test
 * harness. Lives in a {@code time} package, the only location TA-CLOCK-01 exempts. It never reads
 * the system clock: virtual time only moves when told to.
 */
public final class VirtualClock extends Clock {

  private Instant instant;
  private final ZoneId zone;

  public VirtualClock(Instant start) {
    this(start, ZoneOffset.UTC);
  }

  public VirtualClock(Instant start, ZoneId zone) {
    this.instant = start;
    this.zone = zone;
  }

  public void setInstant(Instant target) {
    this.instant = target;
  }

  public void advance(Duration step) {
    this.instant = this.instant.plus(step);
  }

  @Override
  public Instant instant() {
    return instant;
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId newZone) {
    return new VirtualClock(instant, newZone);
  }
}
