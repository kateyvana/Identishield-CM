package cm.identishield.pdp.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Le jeu de règles versionné, lié comme le fait l'application ; variantes pour les tests d'erreur.
 */
public final class TestRuleSet {

  private TestRuleSet() {}

  public static RuleSet load() {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader()
              .load("ruleset", new FileSystemResource("../config/ruleset.yaml"));
      return new Binder(ConfigurationPropertySources.from(sources))
          .bind("ruleset", RuleSet.class)
          .get();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Copie du jeu chargé où {@code path} vaut {@code value} ({@code null} : paramètre retiré). */
  @SuppressWarnings("unchecked")
  public static RuleSet with(String path, Object value) {
    RuleSet rs = load();
    Map<String, Object> root = copy(rs.parameters());
    Map<String, Object> m = root;
    String[] keys = path.split("\\.");
    for (int i = 0; i < keys.length - 1; i++) {
      m = (Map<String, Object>) m.get(keys[i]);
    }
    if (value == null) {
      m.remove(keys[keys.length - 1]);
    } else {
      m.put(keys[keys.length - 1], value);
    }
    return new RuleSet(rs.version(), root);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copy(Map<String, Object> in) {
    Map<String, Object> out = new LinkedHashMap<>();
    in.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m ? copy((Map<String, Object>) m) : v));
    return out;
  }
}
