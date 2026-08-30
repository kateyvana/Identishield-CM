package cm.identishield.core.pdp;

import java.nio.file.Path;
import java.time.Clock;
import javax.net.ssl.SSLContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Racine de composition du client PDP : c'est le seul endroit qui fournit l'horloge concrete
 * ({@link Clock#systemUTC()}) et lit les fichiers PEM. Les classes metier recoivent le temps et le
 * SSLContext injectes (CLAUDE.md §3).
 */
@Configuration
@EnableConfigurationProperties(PdpClientProperties.class)
public class PdpClientConfig {

  // L'horloge est fournie par cm.identishield.core.time.TimeConfig (seul package
  // autorise a lire l'horloge systeme, TA-CLOCK-01) ; injectee ci-dessous.

  @Bean
  SSLContext pdpSslContext(PdpClientProperties props) throws Exception {
    return PemSsl.mutualContext(
        Path.of(props.certPath()), Path.of(props.keyPath()), Path.of(props.caPath()));
  }

  @Bean
  PdpClient pdpClient(PdpClientProperties props, SSLContext pdpSslContext, Clock clock) {
    return new PdpClient(props, pdpSslContext, clock);
  }
}
