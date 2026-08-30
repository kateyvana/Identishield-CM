package cm.identishield.pdp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * @covers EF-17 (version du jeu de regles chargee de la config versionnee, portee par la decision).
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
  void loadsVersionAndNamedParametersFromVersionedConfig() throws IOException {
    RuleSet rs = load();
    assertEquals("rs-0.1.0", rs.version());
    assertTrue(rs.parameters().containsKey("CAR_IMSI"), "les parametres nommes sont charges");
    assertEquals("PT72H", rs.parameters().get("CAR_IMSI"));
  }
}
