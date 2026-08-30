package cm.identishield.core.time;

import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Choix de l'horloge injectée, depuis la configuration versionnée. Par défaut {@code SYSTEM} (temps
 * réel) ; {@code VIRTUAL} fait démarrer une {@link VirtualClock} à {@code virtualStart} pour la
 * simulation/les tests (EF-18). ENF-11 : le temps est une dépendance injectable, pas une lecture
 * directe de l'horloge.
 */
@ConfigurationProperties("isc.clock")
public record ClockProperties(Mode mode, Instant virtualStart) {

  public enum Mode {
    SYSTEM,
    VIRTUAL
  }

  private static final Instant DEFAULT_VIRTUAL_START = Instant.parse("2026-01-01T00:00:00Z");

  public ClockProperties {
    if (mode == null) {
      mode = Mode.SYSTEM;
    }
    if (virtualStart == null) {
      virtualStart = DEFAULT_VIRTUAL_START;
    }
  }
}
