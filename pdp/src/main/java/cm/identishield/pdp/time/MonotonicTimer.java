package cm.identishield.pdp.time;

/**
 * Minuteur monotone d'INSTRUMENTATION (latence de /evaluate, EF-11 latencyMs). Isolé dans le paquet
 * {@code ..time..} exempté de TA-CLOCK-01 : {@link System#nanoTime()} est un compteur monotone pour
 * mesurer des durées, pas l'horloge murale d'une règle métier. Aucune règle de décision ne
 * l'utilise.
 */
public final class MonotonicTimer {

  private MonotonicTimer() {}

  public static long nanos() {
    return System.nanoTime();
  }
}
