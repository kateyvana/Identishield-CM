package cm.identishield.pdp.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jeu de règles versionné du PDP, chargé depuis {@code config/ruleset.yaml} (EF-17, ES-15) : la
 * SOURCE UNIQUE des paramètres nommés, partagée avec le cœur. La {@code version} DOIT figurer dans
 * chaque décision /evaluate (EF-11, portée par la réponse en T2-05). Les paramètres alimenteront le
 * moteur de règles au mois 4 ; aucun n'est codé en dur (porte tools/check_named_params.py).
 */
@ConfigurationProperties("ruleset")
public record RuleSet(String version, Map<String, Object> parameters) {

  // @implements EF-17
  // @implements ES-15
}
