package cm.identishield.pdp.time;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unique endroit du PDP autorisé à fabriquer l'horloge (paquet {@code ..time..}, exempté de
 * TA-CLOCK-01). Le PDP ACCEPTE une horloge injectable, réelle ou virtuelle (EF-18), selon {@code
 * isc.clock.mode} : {@code Clock.systemUTC()} ou une {@link VirtualClock}. Tout le reste (à venir :
 * l'évaluateur, le journal d'audit) reçoit ce Clock injecté et ne lit jamais l'horloge directement
 * (ENF-11, ADR-019).
 */
// @implements EF-18
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
