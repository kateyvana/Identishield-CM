package cm.identishield.core.time;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unique endroit du coeur autorisé à fabriquer l'horloge (paquet {@code ..time..}, exempté de
 * TA-CLOCK-01). Fournit le {@link Clock} injecté partout ailleurs : soit {@code Clock.systemUTC()}
 * (temps réel), soit une {@link VirtualClock} (simulation), selon {@code isc.clock.mode}. Tout le
 * reste reçoit ce Clock injecté et ne lit jamais l'horloge directement (CLAUDE.md §3, ENF-11,
 * ADR-019).
 */
// @implements ENF-11
@Configuration
@EnableConfigurationProperties(ClockProperties.class)
public class TimeConfig {

  @Bean
  public Clock clock(ClockProperties props) {
    return switch (props.mode()) {
      case SYSTEM -> Clock.systemUTC();
      case VIRTUAL -> new VirtualClock(props.virtualStart());
    };
  }
}
