package cm.identishield.core.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Unique endroit du coeur autorise a fabriquer l'horloge systeme (package {@code ..time..}, exempte
 * de TA-CLOCK-01). Tout le reste recoit ce {@link Clock} injecte et ne lit jamais l'horloge
 * directement (CLAUDE.md §3, ENF-11).
 */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
