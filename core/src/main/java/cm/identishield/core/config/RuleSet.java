package cm.identishield.core.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jeu de règles versionné, chargé depuis {@code config/ruleset.yaml} — la SOURCE UNIQUE des
 * paramètres nommés (ES-15), partagée avec le PDP. Le cœur en lit CAP_FAILCLOSED pour sa politique
 * fail-closed (ES-11, ratifié le 2026-10-15) ; aucun paramètre n'est codé en dur (porte
 * tools/check_named_params.py).
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

  /**
   * ES-11 (ratifié par la propriétaire le 2026-10-15) : PDP indisponible, toute sortie au-dessus de
   * CAP_FAILCLOSED.xaf (FCFA) est refusée.
   */
  // @implements ES-11
  public long capFailClosedXaf() {
    Object cap = parameters == null ? null : parameters.get("CAP_FAILCLOSED");
    if (cap instanceof Map<?, ?> m && m.get("xaf") instanceof Number n) {
      return n.longValue();
    }
    throw new IllegalStateException("CAP_FAILCLOSED.xaf absent du jeu de règles versionné");
  }

  /**
   * ES-06 : résolution d'un STEP_UP sur un canal, depuis STEP_UP_POLICY.{@code channelKey} (app ou
   * ussd) du jeu de règles (ADR-016 §3, ADR-016-A).
   */
  public String stepUpPolicy(String channelKey) {
    if (parameters != null
        && parameters.get("STEP_UP_POLICY") instanceof Map<?, ?> m
        && m.get(channelKey) instanceof CharSequence s) {
      return s.toString();
    }
    throw new IllegalStateException(
        "STEP_UP_POLICY." + channelKey + " absent du jeu de règles versionné");
  }

  /** Délai d'une exécution différée (STEP_UP résolu par DEFER), depuis DEFER du jeu de règles. */
  public Duration defer() {
    Object v = parameters == null ? null : parameters.get("DEFER");
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("DEFER absent du jeu de règles versionné");
  }

  /** Carence post-changement d'IMSI (fenêtre PROBATION), depuis CAR_IMSI du jeu de règles. */
  public Duration carImsi() {
    Object v = parameters == null ? null : parameters.get("CAR_IMSI");
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("CAR_IMSI absent du jeu de règles versionné");
  }

  /** Longueur de l'OTP (chiffres décimaux), depuis OTP.length du jeu de règles (EF-03). */
  public int otpLength() {
    if (parameters != null
        && parameters.get("OTP") instanceof Map<?, ?> m
        && m.get("length") instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalStateException("OTP.length absent du jeu de règles versionné");
  }

  /** Durée de validité de l'OTP, depuis OTP.ttl du jeu de règles (EF-03). */
  public Duration otpTtl() {
    if (parameters != null
        && parameters.get("OTP") instanceof Map<?, ?> m
        && m.get("ttl") instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("OTP.ttl absent du jeu de règles versionné");
  }

  /**
   * Essais d'OTP d'un enrôlement avant qu'un nouvel OTP soit obligatoire (EF-03), depuis
   * OTP_ATTEMPTS du jeu de règles (carte B3a).
   */
  // @implements EF-03
  public int otpAttempts() {
    if (parameters != null && parameters.get("OTP_ATTEMPTS") instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalStateException("OTP_ATTEMPTS absent du jeu de règles versionné");
  }

  /**
   * Durée de vie d'un enrôlement entamé (ES-25 : la liaison ne suit la validation de l'OTP que dans
   * cette fenêtre), depuis ENROL_TTL du jeu de règles (carte B3a).
   */
  // @implements ES-25
  public Duration enrolTtl() {
    Object v = parameters == null ? null : parameters.get("ENROL_TTL");
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("ENROL_TTL absent du jeu de règles versionné");
  }

  /**
   * Échéance fixe du jeton de session de l'application (carte B3a, décision 4 : aucun autre
   * minuteur), depuis APP_SESSION_TTL du jeu de règles. Aucune exigence du SRS ne nomme cette
   * échéance : le marqueur renvoie au paramètre nommé versionné (ES-15).
   */
  // @implements ES-15
  public Duration appSessionTtl() {
    Object v = parameters == null ? null : parameters.get("APP_SESSION_TTL");
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("APP_SESSION_TTL absent du jeu de règles versionné");
  }
}
