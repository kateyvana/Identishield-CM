package cm.identishield.core.mobile;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.net.ssl.SSLContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Racine de composition des routes mobiles : le client /device/* réutilise l'adresse et le
 * SSLContext mTLS du client PDP, avec son PROPRE délai ; la session lit APP_SESSION_TTL du jeu de
 * règles versionné.
 */
@Configuration
@EnableConfigurationProperties(DeviceClientProperties.class)
public class MobileConfig {

  @Bean
  DeviceClient deviceClient(
      PdpClientProperties pdp,
      DeviceClientProperties props,
      SSLContext pdpSslContext,
      ObjectMapper json) {
    return new DeviceClient(pdp.baseUrl(), props.timeoutMs(), pdpSslContext, json);
  }

  @Bean
  SessionTokens sessionTokens(JdbcTemplate jdbc, Clock clock, RuleSet ruleSet) {
    return new SessionTokens(jdbc, clock, ruleSet.appSessionTtl());
  }
}
