package cm.identishield.pdp.config;

import java.time.Duration;
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

  /** ES-10 : durée de validité d'un nonce de signature (NONCE_TTL, durée ISO). */
  public Duration nonceTtl() {
    Object v = parameters == null ? null : parameters.get("NONCE_TTL");
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("NONCE_TTL absent du jeu de règles versionné");
  }
}
