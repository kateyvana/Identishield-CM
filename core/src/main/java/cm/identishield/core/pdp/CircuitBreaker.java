package cm.identishield.core.pdp;

import java.time.Clock;

/**
 * Disjoncteur minimal, sans dependance (§4 : aucune bibliotheque de resilience hors stack ; §8 :
 * sobriete). Trois etats : FERME (laisse passer), OUVERT (bloque) apres {@code failureThreshold}
 * echecs consecutifs, puis SEMI_OUVERT apres {@code cooldownMillis} pour laisser passer un appel
 * d'essai.
 *
 * <p>Le temps est injecte via {@link Clock} : la classe ne lit jamais l'horloge systeme directement
 * (CLAUDE.md §3, ENF-11).
 */
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final long cooldownMillis;
  private final Clock clock;

  private State state = State.CLOSED;
  private int consecutiveFailures = 0;
  private long openedAtMillis = 0;

  public CircuitBreaker(int failureThreshold, long cooldownMillis, Clock clock) {
    this.failureThreshold = failureThreshold;
    this.cooldownMillis = cooldownMillis;
    this.clock = clock;
  }

  /** Vrai si un appel est autorise maintenant (et bascule OUVERT -> SEMI_OUVERT). */
  public synchronized boolean allowRequest() {
    if (state == State.OPEN) {
      if (clock.millis() - openedAtMillis >= cooldownMillis) {
        state = State.HALF_OPEN;
        return true;
      }
      return false;
    }
    return true; // CLOSED ou HALF_OPEN
  }

  public synchronized void recordSuccess() {
    consecutiveFailures = 0;
    state = State.CLOSED;
  }

  public synchronized void recordFailure() {
    consecutiveFailures++;
    // Un echec en SEMI_OUVERT rouvre immediatement ; sinon on ouvre au seuil.
    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
      state = State.OPEN;
      openedAtMillis = clock.millis();
    }
  }

  public synchronized State state() {
    return state;
  }
}
