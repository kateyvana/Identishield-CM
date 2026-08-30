package cm.identishield.core.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jeu de règles versionné, chargé depuis {@code config/ruleset.yaml} — la SOURCE UNIQUE des
 * paramètres nommés (ES-15), partagée avec le PDP. Le cœur en lit CAP_PROB pour sa politique
 * fail-closed (ES-11) ; aucun paramètre n'est codé en dur (porte tools/check_named_params.py).
 */
@ConfigurationProperties("ruleset")
public record RuleSet(String version, Map<String, Object> parameters) {

  // @implements ES-15

  /** Plafond quotidien de sortie en probation (FCFA), depuis CAP_PROB du jeu de règles. */
  public long capProbDailyXaf() {
    Object capProb = parameters == null ? null : parameters.get("CAP_PROB");
    if (capProb instanceof Map<?, ?> m && m.get("dailyXaf") instanceof Number n) {
      return n.longValue();
    }
    throw new IllegalStateException("CAP_PROB.dailyXaf absent du jeu de règles versionné");
  }
}
