package cm.identishield.core.hlr;

import java.time.Duration;
import java.time.Instant;

/**
 * Fenêtre CAR_IMSI : fonction PURE de (instant du dernier changement d'IMSI, instant courant, durée
 * CAR_IMSI). Recalculée à chaque évaluation, sans état mutable (OB-1). Le cœur l'expose pour que la
 * fenêtre soit testable dès le mois 2 ; la DÉCISION qui en découle (PROBATION, ES-01b) reste au PDP
 * au mois 4.
 */
public final class CarImsiWindow {

  private CarImsiWindow() {}

  /**
   * Vrai si {@code now} est dans la fenêtre CAR_IMSI ouverte par un changement d'IMSI à {@code
   * changedAt} : {@code (now - changedAt) < carImsi}. La borne est stricte — à exactement CAR_IMSI
   * la fenêtre est close.
   */
  public static boolean isWithin(Instant changedAt, Instant now, Duration carImsi) {
    return Duration.between(changedAt, now).compareTo(carImsi) < 0;
  }
}
