package cm.identishield.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/**
 * Paramètres de session et de PIN de l'application (P-09, P-11 ; rs-0.10.0, carte B4) : lus du jeu
 * de règles versionné, jamais écrits en dur ; absents → échec explicite au démarrage.
 *
 * <p>@covers ES-15
 */
class RuleSetSessionTest {

  private RuleSet load() throws IOException {
    return new Binder(
            ConfigurationPropertySources.from(
                new YamlPropertySourceLoader()
                    .load("ruleset", new FileSystemResource("../config/ruleset.yaml"))))
        .bind("ruleset", RuleSet.class)
        .get();
  }

  @Test
  void theThreeSessionDurations_andThePinLock_comeFromTheVersionedConfig() throws IOException {
    RuleSet rs = load();
    // Ordre imposé par P-09 : arrière-plan < inactivité < durée maximale.
    assertTrue(rs.appSessionBackgroundTtl().compareTo(rs.appSessionTtl()) < 0);
    assertTrue(rs.appSessionTtl().compareTo(rs.appSessionMaxTtl()) < 0);
    assertTrue(rs.appPinMaxAttempts() > 1);
    assertTrue(rs.appPinOriginLock().compareTo(Duration.ZERO) > 0);
  }

  @Test
  void aMissingParameter_failsLoudly() {
    RuleSet bare = new RuleSet("rs-test", Map.of("APP_SESSION_TTL", "PT5M"));
    assertEquals(Duration.ofMinutes(5), bare.appSessionTtl());
    assertThrows(IllegalStateException.class, bare::appSessionBackgroundTtl);
    assertThrows(IllegalStateException.class, bare::appSessionMaxTtl);
    assertThrows(IllegalStateException.class, bare::appPinMaxAttempts);
    assertThrows(IllegalStateException.class, bare::appPinOriginLock);
  }
}
