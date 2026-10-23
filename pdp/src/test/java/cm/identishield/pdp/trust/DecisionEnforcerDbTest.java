package cm.identishield.pdp.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.rules.Score.Decision;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.DecisionEnforcer.Effect;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Application d'une décision FREEZE (carte A3) par la machine à états A1, sur une VRAIE base
 * (init.sql, rôle isc_pdp_app) : le compte passe à FROZEN pour TTL_FREEZE, une période CAR_IMSI en
 * cours n'est ni consommée ni remise à zéro (A1-2), ce gel par décision expire (A1-3). Durées lues
 * dans config/ruleset.yaml.
 *
 * <p>Sans base (ISC_TEST_PG_URL absente), la classe est ignorée ; tools/verify_trust_state.sh la
 * lance contre une base jetable dans la VM.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-04
 *
 * <p>@covers EF-13
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class DecisionEnforcerDbTest {

  private static final Instant T0 = Instant.parse("2026-10-16T08:00:00Z");
  private static final Duration HOUR = Duration.ofHours(1);

  /** Solde figé à l'entrée en PROBATION (V4, carte A4) ; valeur de test sans incidence ici. */
  private static final long ENTRY_BALANCE = 100_000;

  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private final RuleSet rules = TestRuleSet.load();
  private final VirtualClock clock = new VirtualClock(T0);
  private final String acc = "acc-a3-" + UUID.randomUUID();
  private TrustStateMachine trust;
  private DecisionEnforcer enforcer;

  @BeforeAll
  static void connect() {
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
    trust =
        new TrustStateMachine(
            jdbc, transactions, new AuditLog(jdbc, new ObjectMapper()), clock, rules);
    enforcer = new DecisionEnforcer(trust);
  }

  private int transitions() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pdp.trust_transition WHERE account_id = ?", Integer.class, acc);
  }

  @Test
  void aFreezeDecision_freezesTheAccountForTtlFreeze_thenItExpires() {
    trust.enrolled(acc, "624010000000123");
    assertEquals(
        Effect.ACCOUNT_FROZEN, enforcer.apply(acc, UUID.randomUUID().toString(), Decision.FREEZE));
    Trust t = trust.current(acc);
    assertEquals(State.FROZEN, t.state());
    assertEquals(FreezeCause.FREEZE_DECISION, t.freezeCause());
    assertEquals(T0.plus(rules.ttlFreeze()), t.freezeUntil());
    clock.setInstant(T0.plus(rules.ttlFreeze()));
    assertEquals(State.TRUSTED, trust.current(acc).state()); // A1-3 : ce gel-ci expire
  }

  @Test
  void aFreezeDuringProbation_neitherConsumesNorResetsCarImsi() {
    trust.imsiChanged(acc, "624010000000123", "624010000000999", T0, ENTRY_BALANCE);
    clock.setInstant(T0.plus(HOUR));
    assertEquals(
        Effect.ACCOUNT_FROZEN, enforcer.apply(acc, UUID.randomUUID().toString(), Decision.FREEZE));
    assertEquals(T0.plus(rules.carImsi()), trust.current(acc).probationUntil());
    clock.setInstant(T0.plus(HOUR).plus(rules.ttlFreeze()));
    Trust after = trust.current(acc);
    assertEquals(State.PROBATION, after.state()); // A1-2 : échéance d'origine conservée
    assertEquals(T0.plus(rules.carImsi()), after.probationUntil());
  }

  @Test
  void stepUpAndAllow_neverChangeTheAccountState() {
    trust.enrolled(acc, "624010000000123");
    assertEquals(Effect.NONE, enforcer.apply(acc, UUID.randomUUID().toString(), Decision.STEP_UP));
    assertEquals(Effect.NONE, enforcer.apply(acc, UUID.randomUUID().toString(), Decision.ALLOW));
    assertEquals(State.TRUSTED, trust.current(acc).state());
    assertEquals(0, transitions());
  }

  @Test
  void aFreezeOnAFrozenAccount_isAlreadyFrozen_andWritesNothing() {
    trust.enrolled(acc, "624010000000123");
    enforcer.apply(acc, UUID.randomUUID().toString(), Decision.FREEZE);
    assertEquals(1, transitions());
    assertEquals(
        Effect.ALREADY_FROZEN, enforcer.apply(acc, UUID.randomUUID().toString(), Decision.FREEZE));
    assertEquals(1, transitions());
    assertEquals(T0.plus(rules.ttlFreeze()), trust.current(acc).freezeUntil()); // TTL non prolongé
  }
}
