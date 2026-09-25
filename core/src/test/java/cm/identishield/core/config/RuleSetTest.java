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
    assertEquals("rs-0.3.0", rs.version()); // bump AMD-02 v1.1 : paramètres de ré-enrôlement et mobile
    assertEquals(25000L, rs.capProbDailyXaf(), "CAP_PROB.dailyXaf = valeur v1 d'ISC-TM §7");
  }
}
