package cm.identishield.core.time;

/**
 * Minuteur monotone d'INSTRUMENTATION (mesure de latence du bench). Isole ici, dans le package
 * {@code ..time..} exempte de TA-CLOCK-01 : {@link System#nanoTime()} n'est pas l'horloge murale
 * d'une regle metier, c'est un compteur monotone destine a mesurer des durees. Aucune regle de
 * decision ne l'utilise.
 */
public final class MonotonicTimer {

  private MonotonicTimer() {}

  public static long nanos() {
    return System.nanoTime();
  }
}
