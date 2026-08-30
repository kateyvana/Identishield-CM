package cm.identishield.pdp.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Horloge virtuelle mutable (ADR-019, SPIKE-2) : un {@link java.time.Clock} du JDK dont l'instant
 * est fixé puis avancé arbitrairement, sans attente réelle. Permet d'évaluer à un instant virtuel
 * (SQ-20/SQ-21) ; le PDP l'accepte comme n'importe quelle horloge injectée (EF-18). Isolée dans le
 * paquet {@code ..time..}, seul endroit exempté de TA-CLOCK-01.
 *
 * <p>ADR-019 fixe : abstraction = {@code java.time.Clock} du JDK, pas d'interface maison. Le
 * déterminisme ne vient pas de l'horloge mais de la pureté des règles (OB-1) : l'horloge ne fait
 * que livrer l'instant, lu une seule fois par évaluation.
 */
public final class VirtualClock extends Clock {

  private final ZoneId zone;
  private volatile Instant instant;

  public VirtualClock(Instant start) {
    this(start, ZoneOffset.UTC);
  }

  private VirtualClock(Instant start, ZoneId zone) {
    this.instant = start;
    this.zone = zone;
  }

  /** Fixe l'instant virtuel courant (saut arbitraire). */
  public synchronized void setInstant(Instant newInstant) {
    this.instant = newInstant;
  }

  /** Avance l'instant virtuel d'une durée (avance par lots du simulateur). */
  public synchronized void advance(Duration by) {
    this.instant = this.instant.plus(by);
  }

  @Override
  public Instant instant() {
    return instant;
  }

  @Override
  public long millis() {
    return instant.toEpochMilli();
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
