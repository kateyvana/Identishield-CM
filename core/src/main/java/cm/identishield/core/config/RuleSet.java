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
   * Inactivité maximale d'une session de l'application (P-09, carte B4 : aucune requête de l'USAGER
   * pendant cette durée, le jeton meurt), depuis APP_SESSION_TTL du jeu de règles. Aucune exigence
   * du SRS ne nomme cette échéance : le marqueur renvoie au paramètre nommé versionné (ES-15).
   */
  // @implements ES-15
  public Duration appSessionTtl() {
    return duration("APP_SESSION_TTL");
  }

  /**
   * Verrouillage après passage en arrière-plan (P-09) : aucune requête, même automatique, pendant
   * cette durée, le jeton meurt. Le serveur ne voit pas l'arrière-plan, il mesure l'écart entre
   * deux requêtes (décision de la propriétaire du 2026-10-24). APP_SESSION_BACKGROUND_TTL.
   */
  // @implements ES-15
  public Duration appSessionBackgroundTtl() {
    return duration("APP_SESSION_BACKGROUND_TTL");
  }

  /** Durée maximale d'une session, inconditionnelle (P-09) : APP_SESSION_MAX_TTL. */
  // @implements ES-15
  public Duration appSessionMaxTtl() {
    return duration("APP_SESSION_MAX_TTL");
  }

  /** Échecs de PIN tolérés par origine réseau avant blocage (P-11) : APP_PIN.max_attempts. */
  // @implements ES-15
  public int appPinMaxAttempts() {
    if (parameters != null
        && parameters.get("APP_PIN") instanceof Map<?, ?> m
        && m.get("max_attempts") instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalStateException("APP_PIN.max_attempts absent du jeu de règles versionné");
  }

  /** Durée du blocage d'une origine réseau après le dépassement (P-11) : APP_PIN.origin_lock. */
  // @implements ES-15
  public Duration appPinOriginLock() {
    if (parameters != null
        && parameters.get("APP_PIN") instanceof Map<?, ?> m
        && m.get("origin_lock") instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("APP_PIN.origin_lock absent du jeu de règles versionné");
  }

  /**
   * Échecs de ré-enrôlement par compte à partir desquels le téléphone lié est alerté (ISC-MOB-SPEC
   * §9.2, ES-27 ; même seuil que l'alerte console ES-24) : ALERT_REENROL.count.
   */
  // @implements ES-27
  public int alertReenrolCount() {
    if (parameters != null
        && parameters.get("ALERT_REENROL") instanceof Map<?, ?> m
        && m.get("count") instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalStateException("ALERT_REENROL.count absent du jeu de règles versionné");
  }

  /** Fenêtre glissante du comptage ci-dessus : ALERT_REENROL.window. */
  // @implements ES-27
  public Duration alertReenrolWindow() {
    if (parameters != null
        && parameters.get("ALERT_REENROL") instanceof Map<?, ?> m
        && m.get("window") instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException("ALERT_REENROL.window absent du jeu de règles versionné");
  }

  private Duration duration(String name) {
    Object v = parameters == null ? null : parameters.get(name);
    if (v instanceof CharSequence s) {
      return Duration.parse(s);
    }
    throw new IllegalStateException(name + " absent du jeu de règles versionné");
  }
}
