package cm.identishield.pdp.device;

import cm.identishield.pdp.config.RuleSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Limitation des tentatives de réenrôlement (ISC-SAD EnrolmentThrottle ; ADR-030 ; ES-21),
 * AUTORITAIRE côté PDP, là où le code de récupération est vérifié (ADR-041, amendement du
 * 2026-10-17). Après REENROL_ATTEMPTS échecs, l'ORIGINE réseau et l'IDENTIFIANT D'INSTALLATION de
 * l'appelant attendent LOCK_BASE, doublé à chaque échec suivant, plafonné par LOCK_MAX. Le COMPTE
 * visé est compté (le compteur servira à l'alerte ES-24, carte B5 : rien n'alerte ici) mais JAMAIS
 * bloqué : un tiers qui tape des codes faux ne rend jamais le compte indisponible à son titulaire.
 *
 * <p>Les compteurs vivent en base ({@code pdp.enrolment_attempt}, migration 004) : un redémarrage
 * du PDP n'efface pas une attaque en cours. Un succès efface les clés de l'appelant.
 */
// @implements ES-21
@Component
public class EnrolmentThrottle {

  /** Une clé comptée : son genre et sa valeur. */
  public record Key(String kind, String value) {
    public static Key origin(String v) {
      return new Key("ORIGIN", v);
    }

    public static Key installation(String v) {
      return new Key("INSTALLATION", v);
    }

    public static Key account(String v) {
      return new Key("ACCOUNT", v);
    }
  }

  static final String LOCKED_SQL =
      "SELECT locked_until FROM pdp.enrolment_attempt WHERE kind = ? AND counter_key = ?";
  static final String FAIL_SQL =
      "INSERT INTO pdp.enrolment_attempt (kind, counter_key, failures, updated_at) VALUES (?, ?, 1, ?) "
          + "ON CONFLICT (kind, counter_key) DO UPDATE SET failures = enrolment_attempt.failures + 1, "
          + "updated_at = EXCLUDED.updated_at RETURNING failures";
  static final String LOCK_SQL =
      "UPDATE pdp.enrolment_attempt SET locked_until = ? WHERE kind = ? AND counter_key = ?";
  static final String CLEAR_SQL =
      "DELETE FROM pdp.enrolment_attempt WHERE kind = ? AND counter_key = ?";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RuleSet ruleSet;

  public EnrolmentThrottle(JdbcTemplate jdbc, Clock clock, RuleSet ruleSet) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.ruleSet = ruleSet;
  }

  /** Secondes d'attente restantes (la plus longue des clés), ou vide si l'appelant peut essayer. */
  public Optional<Long> lockedFor(List<Key> keys) {
    Instant now = clock.instant();
    long worst = 0;
    for (Key k : keys) {
      for (OffsetDateTime until :
          jdbc.queryForList(LOCKED_SQL, OffsetDateTime.class, k.kind(), k.value())) {
        if (until != null && until.toInstant().isAfter(now)) {
          long millis = Duration.between(now, until.toInstant()).toMillis();
          worst = Math.max(worst, (millis + 999) / 1000); // arrondi au-dessus, jamais 0
        }
      }
    }
    return worst > 0 ? Optional.of(worst) : Optional.empty();
  }

  /**
   * Un échec pour chaque clé. ORIGIN et INSTALLATION : à partir du REENROL_ATTEMPTS-ième, blocage
   * croissant ; ACCOUNT : comptage seul (ES-21 : jamais de blocage du compte).
   */
  public void failed(List<Key> keys) {
    Instant now = clock.instant();
    long attempts = ruleSet.integer("REENROL_ATTEMPTS");
    Duration base = ruleSet.duration("LOCK_BASE");
    Duration max = ruleSet.duration("LOCK_MAX");
    for (Key k : keys) {
      Integer failures =
          jdbc.queryForObject(FAIL_SQL, Integer.class, k.kind(), k.value(), utc(now));
      if (failures != null && !"ACCOUNT".equals(k.kind()) && failures >= attempts) {
        jdbc.update(
            LOCK_SQL, utc(now.plus(lockAfter(failures, attempts, base, max))), k.kind(), k.value());
      }
    }
  }

  /** Un succès : l'ardoise des clés est effacée. */
  public void succeeded(List<Key> keys) {
    for (Key k : keys) {
      jdbc.update(CLEAR_SQL, k.kind(), k.value());
    }
  }

  /**
   * Délai après le {@code failures}-ième échec (failures >= attempts) : LOCK_BASE × 2^(failures −
   * attempts), plafonné par LOCK_MAX (ADR-030). L'exposant est borné pour que le doublement ne
   * déborde jamais : au-delà, c'est LOCK_MAX de toute façon.
   */
  static Duration lockAfter(long failures, long attempts, Duration base, Duration max) {
    long extra = Math.min(Math.max(0, failures - attempts), 30);
    Duration grown = base.multipliedBy(1L << extra);
    return grown.compareTo(max) > 0 ? max : grown;
  }

  private static OffsetDateTime utc(Instant at) {
    return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }
}
