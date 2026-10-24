package cm.identishield.core.mobile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comptage des échecs de PIN par ORIGINE RÉSEAU (P-11, décision de la propriétaire du 2026-10-10 :
 * APP_PIN.max_attempts échecs bloquent l'origine pendant APP_PIN.origin_lock, JAMAIS le compte). Le
 * compte visé reste disponible à son titulaire depuis une autre origine (même motif qu'ES-21) ;
 * l'attaquant qui tape des PIN depuis sa machine ne bloque que sa machine.
 *
 * <p>En mémoire du cœur, volontairement : le blocage est court, et un redémarrage du cœur remet les
 * compteurs à zéro (limite déclarée, carte B4 : l'attaquant ne redémarre pas le cœur). Les entrées
 * expirées sont retirées au passage pour que la table ne grossisse qu'avec les origines récemment
 * en échec.
 */
// @implements ES-22
public class PinAttempts {

  private record Entry(int failures, Instant lockedUntil) {}

  private final Clock clock;
  private final int maxAttempts;
  private final Duration lock;
  private final Map<String, Entry> byOrigin = new ConcurrentHashMap<>();

  public PinAttempts(Clock clock, int maxAttempts, Duration lock) {
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.lock = lock;
  }

  /** Secondes de blocage restantes pour cette origine, ou vide si elle peut essayer. */
  public Optional<Long> lockedFor(String origin) {
    Instant now = clock.instant();
    Entry e = byOrigin.get(origin);
    if (e == null) {
      return Optional.empty();
    }
    if (e.lockedUntil() != null) {
      if (e.lockedUntil().isAfter(now)) {
        // Arrondi au-dessus : Retry-After ne promet jamais une seconde de trop tôt.
        long millis = Duration.between(now, e.lockedUntil()).toMillis();
        return Optional.of((millis + 999) / 1000);
      }
      byOrigin.remove(origin, e); // blocage échu : l'origine repart de zéro
    }
    return Optional.empty();
  }

  /**
   * Un PIN faux depuis cette origine ; au max_attempts-ième échec, l'origine est bloquée. Une
   * origine déjà bloquée le reste (le blocage n'est ni prolongé ni raccourci) ; un blocage échu
   * repart de zéro.
   */
  public void failed(String origin) {
    Instant now = clock.instant();
    byOrigin.compute(
        origin,
        (o, e) -> {
          if (e != null && e.lockedUntil() != null && e.lockedUntil().isAfter(now)) {
            return e;
          }
          int failures = (e == null || e.lockedUntil() != null ? 0 : e.failures()) + 1;
          return failures >= maxAttempts
              ? new Entry(failures, now.plus(lock))
              : new Entry(failures, null);
        });
  }

  /** Un PIN juste depuis cette origine : ses échecs précédents sont oubliés. */
  public void succeeded(String origin) {
    byOrigin.remove(origin);
  }
}
