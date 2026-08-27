package cm.identishield.spike.clock.time;

import java.util.function.Supplier;

/**
 * Real-time measurement for the no-real-wait proof (SQ-20/SQ-21). Lives in the {@code time}
 * package: TA-CLOCK-01 exempts this location, which is precisely the legitimate form the rule must
 * keep allowing (Q6). Measures the harness, never feeds a rule.
 */
public final class Stopwatch {

  public record Timed<T>(T value, long millis) {}

  private Stopwatch() {}

  public static <T> Timed<T> time(Supplier<T> body) {
    long startNanos = System.nanoTime();
    T value = body.get();
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
    return new Timed<>(value, elapsedMillis);
  }
}
