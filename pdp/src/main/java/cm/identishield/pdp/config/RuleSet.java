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
    return duration("NONCE_TTL");
  }

  /** ES-01b : carence post-changement d'IMSI, durée de la PROBATION. */
  public Duration carImsi() {
    return duration("CAR_IMSI");
  }

  /** Durée d'un gel issu d'une décision FREEZE (A1-3). */
  public Duration ttlFreeze() {
    return duration("TTL_FREEZE");
  }

  /** ES-27 : gel des ré-enrôlements après « ce n'est pas moi ». */
  public Duration reenrolFreeze() {
    return duration("REENROL_FREEZE");
  }

  /** ES-11 : plafond par transaction quand le PDP est indisponible (CAP_FAILCLOSED, carte A3). */
  public long capFailClosedXaf() {
    return integer("CAP_FAILCLOSED.xaf");
  }

  /** Durée ISO d'un paramètre ; {@code path} pointé pour un sous-paramètre, ex. VEL.window. */
  public Duration duration(String path) {
    return Duration.parse(text(path));
  }

  /** Entier d'un paramètre, ex. SCORE.max. */
  public long integer(String path) {
    return Long.parseLong(text(path));
  }

  /**
   * Valeur d'un paramètre, ex. NIGHT_ZONE ou STEP_UP_POLICY.ussd. Absente : erreur, jamais défaut.
   */
  public String text(String path) {
    Object v = parameters;
    for (String key : path.split("\\.")) {
      v = v instanceof Map<?, ?> m ? m.get(key) : null;
    }
    if (v == null || v instanceof Map<?, ?>) {
      throw new IllegalStateException(path + " absent du jeu de règles versionné");
    }
    return v.toString();
  }
}
