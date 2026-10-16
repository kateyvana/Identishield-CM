package cm.identishield.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * @covers ES-15 (parametres nommes charges du jeu de regles versionne, source unique).
 */
class RuleSetTest {

  private RuleSet load() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("ruleset", new FileSystemResource("../config/ruleset.yaml"));
    return new Binder(ConfigurationPropertySources.from(sources))
        .bind("ruleset", RuleSet.class)
        .get();
  }

  @Test
  void loadsVersionAndCapProbFromVersionedConfig() throws IOException {
    RuleSet rs = load();
    // rs-0.9.0, bump unique du mois 4 : STEP_UP_POLICY.app = DEFER ; THR_HIGH.medianWindow ;
    // CODE_LENGTH 20 -> 6 ; APP_CONN_KEEPALIVE et SERVER_CONN_KEEPALIVE (après rs-0.8.0).
    assertEquals("rs-0.9.0", rs.version());
    assertEquals(25000L, rs.capProbDailyXaf(), "CAP_PROB.dailyXaf = valeur v1 d'ISC-TM §7");
  }

  // @covers ES-11
  @Test
  void capFailClosedComesFromTheVersionedConfig_es11() throws IOException {
    // ES-11 ratifié (2026-10-15) : le seuil fail-closed est CAP_FAILCLOSED, distinct de CAP_PROB.
    assertEquals(50000L, load().capFailClosedXaf(), "CAP_FAILCLOSED.xaf = calibration T3-07");
  }
}
