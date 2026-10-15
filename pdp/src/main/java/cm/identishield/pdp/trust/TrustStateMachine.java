package cm.identishield.pdp.trust;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Machine à états de confiance du compte (ISC-SAD v1.4 §7.5) : TRUSTED, PROBATION, FROZEN. LOCKED
 * est hors du mois 4 (T-05). Règles : arbitrages A1-1 à A1-7 de REGISTRE_DECISIONS.
 *
 * <p>Les échéances (fin de CAR_IMSI, TTL_FREEZE) sont évaluées à la lecture (ADR-019) et appliquées
 * à leur instant d'échéance, quel que soit le moment de la lecture : le résultat ne dépend pas de
 * la fréquence des lectures. Chaque transition est écrite dans pdp.trust_transition et dans le
 * journal d'audit, dans la même transaction que l'état. Une transition interdite lève {@link
 * Refused} : la transaction est annulée, rien ne change.
 *
 * <p>A1 expose les opérations ; leur branchement (événement HLR, « ce n'est pas moi » signé par
 * l'appareil lié, levée avec preuve ES-08) appartient aux cartes A3 et B5.
 *
 * <p>Revue ligne à ligne requise : machine à états, journal d'audit.
 */
@Component
public class TrustStateMachine {

  // @implements EF-13
  // @implements ES-01b
  // @implements ES-03c
  // @implements ES-27

  public enum State {
    TRUSTED,
    PROBATION,
    FROZEN
  }

  /** Origine d'un gel (A1-3) : seul le gel issu d'une décision FREEZE expire (TTL_FREEZE). */
  public enum FreezeCause {
    FREEZE_DECISION,
    NOT_ME
  }

  /** Alerte à laquelle répond « ce n'est pas moi » (A1-4). */
  public enum Alert {
    SIM_CHANGE,
    REENROL_ATTEMPT
  }

  /** État de confiance d'un compte. {@code since} est nul pour un compte jamais vu (A1-5). */
  public record Trust(
      String accountId,
      State state,
      Instant since,
      String referenceImsi,
      String currentImsi,
      Instant probationUntil,
      FreezeCause freezeCause,
      Instant freezeUntil,
      Instant reenrolFrozenUntil) {}

  /** Transition interdite : refusée, l'état ne change pas. */
  public static final class Refused extends RuntimeException {
    Refused(String message) {
      super(message);
    }
  }

  static final String EVENT_PROBATION_ENTERED = "TRUST_PROBATION_ENTERED";
  static final String EVENT_PROBATION_EXPIRED = "TRUST_PROBATION_EXPIRED";
  static final String EVENT_FROZEN = "TRUST_FROZEN";
  static final String EVENT_UNFROZEN = "TRUST_UNFROZEN";
  static final String EVENT_REENROL_FROZEN = "REENROL_FROZEN";

  private static final String ENSURE_SQL =
      "INSERT INTO pdp.trust_state(account_id, state, since) VALUES (?, 'TRUSTED', ?)"
          + " ON CONFLICT (account_id) DO NOTHING";
  private static final String LOCK_SQL =
      "SELECT * FROM pdp.trust_state WHERE account_id = ? FOR UPDATE";
  private static final String SAVE_SQL =
      "UPDATE pdp.trust_state SET state = ?, since = ?, reference_imsi = ?, current_imsi = ?,"
          + " probation_until = ?, freeze_cause = ?, freeze_until = ?, reenrol_frozen_until = ?"
          + " WHERE account_id = ?";
  private static final String TRANSITION_SQL =
      "INSERT INTO pdp.trust_transition(account_id, from_state, to_state, event, cause, occurred_at)"
          + " VALUES (?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AuditLog auditLog;
  private final Clock clock;
  private final RuleSet rules;

  public TrustStateMachine(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      AuditLog auditLog,
      Clock clock,
      RuleSet rules) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
    this.auditLog = auditLog;
    this.clock = clock;
    this.rules = rules;
  }

  /** État courant, échéances appliquées. Un compte jamais vu est TRUSTED (A1-5). */
  public Trust current(String accountId) {
    return tx.execute(
        s -> {
          Instant now = clock.instant();
          return lock(accountId)
              .map(
                  r -> {
                    settle(r, now);
                    save(r);
                    return r.snapshot();
                  })
              .orElseGet(
                  () ->
                      new Trust(
                          accountId, State.TRUSTED, null, null, null, null, null, null, null));
        });
  }

  /**
   * Enrôlement ou ré-enrôlement. Le premier fixe l'IMSI de référence (A1-5) ; aucun ne change
   * l'état : la PROBATION ne vient que d'un changement d'IMSI (T-03, ES-03c).
   */
  public Trust enrolled(String accountId, String observedImsi) {
    return mutate(
        accountId,
        (r, now) -> {
          if (r.referenceImsi == null) {
            r.referenceImsi = observedImsi;
          }
          if (r.currentImsi == null) {
            r.currentImsi = observedImsi;
          }
        });
  }

  /**
   * Changement d'IMSI (ES-01b, ES-03c) : PROBATION jusqu'à {@code changedAt} + CAR_IMSI ; un
   * nouveau changement en PROBATION relance la période. Pendant un gel, l'état reste FROZEN et
   * l'IMSI de référence et l'échéance sont mis à jour (A1-6).
   */
  public Trust imsiChanged(String accountId, String oldImsi, String newImsi, Instant changedAt) {
    return mutate(
        accountId,
        (r, now) -> {
          String known = r.currentImsi != null ? r.currentImsi : r.referenceImsi;
          if (newImsi.equals(known)) {
            throw new Refused("IMSI unchanged for " + accountId);
          }
          State from = r.state;
          if (from == State.FROZEN) {
            r.referenceImsi = newImsi;
          } else {
            if (r.referenceImsi == null) {
              r.referenceImsi = oldImsi;
            }
            r.state = State.PROBATION;
            r.since = changedAt;
          }
          r.currentImsi = newImsi;
          r.probationUntil = changedAt.plus(rules.carImsi());
          transition(
              r,
              from,
              EVENT_PROBATION_ENTERED,
              "IMSI_CHANGE",
              changedAt,
              Map.of("probationUntil", iso(r.probationUntil)));
        });
  }

  /** Décision FREEZE du PDP : FROZEN pour TTL_FREEZE (A1-3). Refusée si le compte est déjà gelé. */
  public Trust frozenByDecision(String accountId, String decisionId) {
    return mutate(
        accountId,
        (r, now) -> {
          if (r.state == State.FROZEN) {
            throw new Refused("account already frozen: " + accountId);
          }
          State from = r.state;
          r.state = State.FROZEN;
          r.since = now;
          r.freezeCause = FreezeCause.FREEZE_DECISION;
          r.freezeUntil = now.plus(rules.ttlFreeze());
          // Pas de clé « decisionId » : elle est unique dans le journal (ENF-03), portée par la
          // décision elle-même.
          transition(
              r,
              from,
              EVENT_FROZEN,
              FreezeCause.FREEZE_DECISION.name(),
              now,
              Map.of("freezeDecisionId", decisionId, "freezeUntil", iso(r.freezeUntil)));
        });
  }

  /**
   * « Ce n'est pas moi » (ES-27, A1-4). Alerte de changement de SIM : FROZEN sans expiration (A1-3)
   * et ré-enrôlements gelés ; un gel issu d'une décision devient un gel « ce n'est pas moi ».
   * Alerte de tentative de ré-enrôlement : ré-enrôlements gelés seulement, l'état ne change pas.
   */
  public Trust notMe(String accountId, Alert alert) {
    return mutate(
        accountId,
        (r, now) -> {
          if (alert == Alert.SIM_CHANGE) {
            if (r.freezeCause == FreezeCause.NOT_ME) {
              throw new Refused("account already frozen by NOT_ME: " + accountId);
            }
            State from = r.state;
            if (from != State.FROZEN) {
              r.since = now;
            }
            r.state = State.FROZEN;
            r.freezeCause = FreezeCause.NOT_ME;
            r.freezeUntil = null;
            transition(r, from, EVENT_FROZEN, FreezeCause.NOT_ME.name(), now, Map.of());
          }
          r.reenrolFrozenUntil = now.plus(rules.reenrolFreeze());
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("event", EVENT_REENROL_FROZEN);
          p.put("accountId", r.accountId);
          p.put("alert", alert.name());
          p.put("reenrolFrozenUntil", iso(r.reenrolFrozenUntil));
          auditLog.append(p, now);
        });
  }

  /**
   * Levée explicite d'un gel, quelle qu'en soit l'origine : rend l'état en vigueur avant le gel
   * (A1-2). La vérification de la preuve (ES-08, quatre-yeux) appartient à l'appelant (carte A3).
   */
  public Trust release(String accountId, String actor) {
    return mutate(
        accountId,
        (r, now) -> {
          if (r.state != State.FROZEN) {
            throw new Refused("account not frozen: " + accountId);
          }
          unfreeze(r, now, "RELEASE", Map.of("actor", actor));
        });
  }

  private Trust mutate(String accountId, BiConsumer<Row, Instant> operation) {
    return tx.execute(
        s -> {
          Instant now = clock.instant();
          jdbc.update(ENSURE_SQL, accountId, utc(now));
          Row r = lock(accountId).orElseThrow();
          settle(r, now);
          operation.accept(r, now);
          settle(r, now);
          save(r);
          return r.snapshot();
        });
  }

  /** Applique, dans l'ordre et à leur instant, les échéances passées (ADR-019). */
  private void settle(Row r, Instant now) {
    while (true) {
      if (r.state == State.PROBATION && !now.isBefore(r.probationUntil)) {
        Instant at = r.probationUntil;
        endProbation(r);
        r.state = State.TRUSTED;
        r.since = at;
        transition(r, State.PROBATION, EVENT_PROBATION_EXPIRED, "CAR_IMSI_EXPIRED", at, Map.of());
      } else if (r.freezeCause == FreezeCause.FREEZE_DECISION && !now.isBefore(r.freezeUntil)) {
        unfreeze(r, r.freezeUntil, "TTL_FREEZE_EXPIRED", Map.of());
      } else {
        return;
      }
    }
  }

  /**
   * Sortie de FROZEN (A1-2) : PROBATION si l'échéance CAR_IMSI d'origine n'est pas atteinte à
   * {@code at}, sinon TRUSTED.
   */
  private void unfreeze(Row r, Instant at, String cause, Map<String, Object> extra) {
    boolean probationRunning = r.probationUntil != null && at.isBefore(r.probationUntil);
    if (!probationRunning) {
      endProbation(r);
    }
    r.state = probationRunning ? State.PROBATION : State.TRUSTED;
    r.since = at;
    r.freezeCause = null;
    r.freezeUntil = null;
    transition(r, State.FROZEN, EVENT_UNFROZEN, cause, at, extra);
  }

  /** Fin d'une période CAR_IMSI : l'IMSI courant devient l'IMSI de référence (A1-1). */
  private static void endProbation(Row r) {
    if (r.probationUntil != null && r.currentImsi != null) {
      r.referenceImsi = r.currentImsi;
    }
    r.probationUntil = null;
  }

  private void transition(
      Row r, State from, String event, String cause, Instant at, Map<String, Object> extra) {
    jdbc.update(TRANSITION_SQL, r.accountId, from.name(), r.state.name(), event, cause, utc(at));
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("event", event);
    p.put("accountId", r.accountId);
    p.put("fromState", from.name());
    p.put("toState", r.state.name());
    p.put("cause", cause);
    p.put("occurredAt", iso(at));
    p.putAll(extra);
    auditLog.append(p, clock.instant());
  }

  private Optional<Row> lock(String accountId) {
    List<Row> rows = jdbc.query(LOCK_SQL, (rs, i) -> Row.read(rs), accountId);
    return rows.stream().findFirst();
  }

  private void save(Row r) {
    jdbc.update(
        SAVE_SQL,
        r.state.name(),
        utc(r.since),
        r.referenceImsi,
        r.currentImsi,
        utc(r.probationUntil),
        r.freezeCause == null ? null : r.freezeCause.name(),
        utc(r.freezeUntil),
        utc(r.reenrolFrozenUntil),
        r.accountId);
  }

  private static OffsetDateTime utc(Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private static String iso(Instant i) {
    return i.toString();
  }

  /** Ligne pdp.trust_state, modifiée pendant une seule transaction. */
  private static final class Row {
    String accountId;
    State state;
    Instant since;
    String referenceImsi;
    String currentImsi;
    Instant probationUntil;
    FreezeCause freezeCause;
    Instant freezeUntil;
    Instant reenrolFrozenUntil;

    static Row read(ResultSet rs) throws SQLException {
      Row r = new Row();
      r.accountId = rs.getString("account_id");
      r.state = State.valueOf(rs.getString("state"));
      r.since = instant(rs, "since");
      r.referenceImsi = rs.getString("reference_imsi");
      r.currentImsi = rs.getString("current_imsi");
      r.probationUntil = instant(rs, "probation_until");
      String cause = rs.getString("freeze_cause");
      r.freezeCause = cause == null ? null : FreezeCause.valueOf(cause);
      r.freezeUntil = instant(rs, "freeze_until");
      r.reenrolFrozenUntil = instant(rs, "reenrol_frozen_until");
      return r;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
      OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
      return t == null ? null : t.toInstant();
    }

    Trust snapshot() {
      return new Trust(
          accountId,
          state,
          since,
          referenceImsi,
          currentImsi,
          probationUntil,
          freezeCause,
          freezeUntil,
          reenrolFrozenUntil);
    }
  }
}
