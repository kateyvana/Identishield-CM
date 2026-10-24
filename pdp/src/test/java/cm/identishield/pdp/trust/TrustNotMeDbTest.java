package cm.identishield.pdp.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.TrustStateMachine.Alert;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.Refused;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.yaml.snakeyaml.Yaml;

/**
 * POST /trust/{accountId}/not-me (ADR-041, amendement du 2026-10-25 ; ES-27 ; arbitrage A1-4) : la
 * réponse « ce n'est pas moi » du titulaire, telle que la machine l'applique et l'écrit dans l'audit
 * avec l'{@code alertId}. Sur la vraie base (variable ISC_TEST_PG_URL).
 *
 * <p>@covers ES-27
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class TrustNotMeDbTest {

  private static final Instant T0 = Instant.parse("2026-10-25T08:00:00Z");
  private static final String IMSI_VICTIM = "624010000000123";
  private static final String ALERT_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  private static RuleSet rules;
  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private final VirtualClock clock = new VirtualClock(T0);
  private final String acc = "acc-notme-" + UUID.randomUUID();
  private TrustStateMachine m;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void connect() throws Exception {
    try (InputStream in = Files.newInputStream(Path.of("../config/ruleset.yaml"))) {
      Map<String, Object> rs =
          (Map<String, Object>) new Yaml().<Map<String, Object>>load(in).get("ruleset");
      rules = new RuleSet((String) rs.get("version"), (Map<String, Object>) rs.get("parameters"));
    }
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getenv("ISC_TEST_PG_URL"),
            System.getenv("ISC_TEST_PG_USER"),
            System.getenv("ISC_TEST_PG_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    transactions = new DataSourceTransactionManager(ds);
  }

  @BeforeEach
  void machine() {
    m =
        new TrustStateMachine(
            jdbc, transactions, new AuditLog(jdbc, new ObjectMapper()), clock, rules);
  }

  /** « événement|alertId » des entrées d'audit du compte, dans l'ordre de la chaîne. */
  private List<String> auditEventsWithAlertId() {
    return jdbc.queryForList(
        "SELECT (payload ->> 'event') || '|' || coalesce(payload ->> 'alertId', '-')"
            + " FROM pdp.audit_entry WHERE payload ->> 'accountId' = ? ORDER BY chain_index",
        String.class,
        acc);
  }

  @Test
  void simChange_freezesWithoutDeadline_freezesReenrolments_andTracesTheAlertIdInBothEvents() {
    m.enrolled(acc, IMSI_VICTIM);
    Trust t = m.notMe(acc, Alert.SIM_CHANGE, Map.of("alertId", ALERT_ID));

    assertEquals(State.FROZEN, t.state());
    assertEquals(FreezeCause.NOT_ME, t.freezeCause());
    assertNull(t.freezeUntil(), "gel NOT_ME : sans échéance");
    assertEquals(T0.plus(rules.reenrolFreeze()), t.reenrolFrozenUntil());
    assertEquals(
        List.of("TRUST_FROZEN|" + ALERT_ID, "REENROL_FROZEN|" + ALERT_ID),
        auditEventsWithAlertId(),
        "les deux événements portent l'alertId");

    // Déjà gelé NOT_ME : refusé (la route répond 409), rien n'est réécrit.
    assertThrows(Refused.class, () -> m.notMe(acc, Alert.SIM_CHANGE, Map.of("alertId", ALERT_ID)));
    assertEquals(2, auditEventsWithAlertId().size());
  }

  @Test
  void reenrolAttempt_freezesReenrolmentsOnly_theStateDoesNotChange_andASecondAnswerExtends() {
    m.enrolled(acc, IMSI_VICTIM);
    Trust t = m.notMe(acc, Alert.REENROL_ATTEMPT, Map.of("alertId", ALERT_ID));
    assertEquals(State.TRUSTED, t.state(), "A1-4 : le titulaire garde sa clé ACTIVE");
    assertNull(t.freezeCause());
    assertEquals(T0.plus(rules.reenrolFreeze()), t.reenrolFrozenUntil());
    assertEquals(List.of("REENROL_FROZEN|" + ALERT_ID), auditEventsWithAlertId());

    clock.advance(Duration.ofHours(1));
    Trust again = m.notMe(acc, Alert.REENROL_ATTEMPT, Map.of("alertId", ALERT_ID));
    assertEquals(State.TRUSTED, again.state());
    assertEquals(
        T0.plus(Duration.ofHours(1)).plus(rules.reenrolFreeze()),
        again.reenrolFrozenUntil(),
        "second REENROL_ATTEMPT : le gel est reprolongé");
  }

  @Test
  void anAccountNeverSeen_isCreatedByTheTransition_asForAnyOther() {
    Trust t = m.notMe(acc, Alert.SIM_CHANGE, Map.of("alertId", ALERT_ID));
    assertEquals(State.FROZEN, t.state());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.trust_state WHERE account_id = ?", Integer.class, acc));
  }
}
