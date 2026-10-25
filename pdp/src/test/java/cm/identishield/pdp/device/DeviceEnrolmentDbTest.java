package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceRegistry.BindingState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Cycle de vie de la clé et code de récupération sur une VRAIE base (init.sql, rôle isc_pdp_app) :
 * l'ALTER du CHECK accepte PENDING et refuse tout autre état ; les GRANT de la section B3a
 * permettent au rôle applicatif d'émettre ET de vérifier un code ; les index uniques gardent une
 * seule clé ACTIVE et une seule clé EN ATTENTE par compte, et un nouvel enregistrement révoque la
 * clé EN ATTENTE précédente ; le fait de liaison suit NONE → PENDING → ACTIVE ; une clé EN ATTENTE
 * ne signe rien.
 *
 * <p>Sans base (ISC_TEST_PG_URL absente), la classe est ignorée ; tools/verify_device_enrolment.sh
 * la lance contre une base jetable dans la VM.
 *
 * <p>@covers ES-18
 *
 * <p>@covers ES-20
 *
 * <p>@covers ES-26
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class DeviceEnrolmentDbTest {

  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");
  private static final RecoveryCodeProperties PROPS = new RecoveryCodeProperties(1_000, 16, 256);

  private static JdbcTemplate jdbc;

  private final String acc = "acc-b3a-" + UUID.randomUUID();
  private DeviceRegistry registry;
  private RecoveryCodes codes;

  @BeforeAll
  static void connect() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getenv("ISC_TEST_PG_URL"),
                System.getenv("ISC_TEST_PG_USER"),
                System.getenv("ISC_TEST_PG_PASSWORD")));
  }

  private void init() {
    registry = new DeviceRegistry(jdbc);
    codes = new RecoveryCodes(jdbc, TestRuleSet.load(), PROPS);
  }

  @Test
  void pendingIsAcceptedByTheDatabase_anyOtherStateIsRefused() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT state FROM pdp.device WHERE id = ?", String.class, id));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE pdp.device SET state = 'BOGUS' WHERE id = ?", id));
  }

  // ---- carte B3b : compteur d'échecs en base (ADR-030) et révocation à l'activation ----------

  @Test
  void theThrottle_countsPerKey_locksOriginAfterNAttempts_neverTheAccount_andClearsOnSuccess() {
    init();
    cm.identishield.pdp.time.VirtualClock clock = new cm.identishield.pdp.time.VirtualClock(T0);
    cm.identishield.pdp.config.RuleSet rs = TestRuleSet.load();
    EnrolmentThrottle throttle = new EnrolmentThrottle(jdbc, clock, rs);
    String origin = "100.64.0." + (int) (Math.random() * 250);
    List<EnrolmentThrottle.Key> keys =
        List.of(
            EnrolmentThrottle.Key.origin(origin + "/" + acc), EnrolmentThrottle.Key.account(acc));
    long attempts = rs.integer("REENROL_ATTEMPTS");
    for (int i = 1; i < attempts; i++) {
      throttle.failed(keys);
      assertTrue(throttle.lockedFor(keys).isEmpty(), "échec " + i + " : encore libre");
    }
    throttle.failed(keys);
    assertEquals(
        Optional.of(rs.duration("LOCK_BASE").toSeconds()),
        throttle.lockedFor(keys),
        "N-ième : LOCK_BASE");
    // Le compte est compté, jamais bloqué.
    assertTrue(throttle.lockedFor(List.of(EnrolmentThrottle.Key.account(acc))).isEmpty());
    assertEquals(
        (int) attempts,
        jdbc.queryForObject(
            "SELECT failures FROM pdp.enrolment_attempt WHERE kind = 'ACCOUNT' AND counter_key = ?",
            Integer.class,
            acc));
    clock.advance(rs.duration("LOCK_BASE"));
    assertTrue(throttle.lockedFor(keys).isEmpty(), "délai écoulé");
    throttle.failed(keys);
    assertEquals(
        Optional.of(rs.duration("LOCK_BASE").multipliedBy(2).toSeconds()),
        throttle.lockedFor(keys),
        "échec suivant : doublé");
    throttle.succeeded(keys);
    assertTrue(throttle.lockedFor(keys).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.enrolment_attempt WHERE counter_key IN (?, ?)",
            Integer.class,
            origin + "/" + acc,
            acc));
  }

  @Test
  void activation_revokesTheOldActiveKey_inTheSameTransaction_andNeverLeavesZeroOrTwoActive() {
    init();
    org.springframework.jdbc.datasource.DriverManagerDataSource ds =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("ISC_TEST_PG_URL"),
            System.getenv("ISC_TEST_PG_USER"),
            System.getenv("ISC_TEST_PG_PASSWORD"));
    DeviceEnrolment enrolment =
        new DeviceEnrolment(
            new AttestationVerifier(),
            registry,
            codes,
            new EnrolmentThrottle(
                jdbc, java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC), TestRuleSet.load()),
            org.mockito.Mockito.mock(cm.identishield.pdp.trust.TrustStateMachine.class),
            org.mockito.Mockito.mock(cm.identishield.pdp.audit.AuditLog.class),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
            java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
    String old = registry.registerPending(acc, "cGs=", T0);
    String oldCode = codes.issue(old, T0);
    assertTrue(
        enrolment.activate(acc, old, oldCode), "première activation : aucune clé à révoquer");
    String fresh = registry.registerPending(acc, "cGsy", T0);
    String freshCode = codes.issue(fresh, T0);
    // Code faux : rien ne bouge, l'ancienne reste ACTIVE (la révocation est annulée avec la
    // transaction).
    assertFalse(enrolment.activate(acc, fresh, "WRONG1"));
    assertEquals(Optional.of("ACTIVE"), registry.stateOf(acc, old));
    assertEquals(Optional.of("PENDING"), registry.stateOf(acc, fresh));
    // Bon code : l'ancienne RÉVOQUÉE et la nouvelle ACTIVE, ensemble.
    assertTrue(enrolment.activate(acc, fresh, freshCode));
    assertEquals(Optional.of("REVOKED"), registry.stateOf(acc, old));
    assertEquals(Optional.of("ACTIVE"), registry.stateOf(acc, fresh));
    assertEquals(fresh, registry.findActive(acc).orElseThrow().id());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.device WHERE account_id = ? AND state = 'ACTIVE'",
            Integer.class,
            acc));
  }

  /** Carte B4 : seule une clé ACTIVE a un défi de session ; l'état d'une clé nommée est énoncé. */
  @Test
  void onlyAnActiveKey_isFoundById_andItsStateIsStated() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    assertTrue(registry.findActiveById(id).isEmpty(), "EN ATTENTE : pas de défi");
    assertEquals(Optional.of("PENDING"), registry.stateOf(acc, id));
    assertTrue(registry.activate(acc, id));
    assertEquals(acc, registry.findActiveById(id).orElseThrow().accountId());
    assertEquals(Optional.of("ACTIVE"), registry.stateOf(acc, id));
    jdbc.update("UPDATE pdp.device SET state = 'REVOKED' WHERE id = ?", id);
    assertTrue(registry.findActiveById(id).isEmpty(), "RÉVOQUÉE : plus de défi");
    assertEquals(Optional.of("REVOKED"), registry.stateOf(acc, id));
    assertEquals(
        Optional.empty(), registry.stateOf("acc-autre", id), "jamais pour un autre compte");
  }

  @Test
  void theStateCheck_acceptsExactlyPendingActiveRevoked() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    for (String state : new String[] {"ACTIVE", "REVOKED", "PENDING"}) {
      assertEquals(
          1, jdbc.update("UPDATE pdp.device SET state = ? WHERE id = ?", state, id), state);
    }
    for (String state : new String[] {"BOGUS", "active", ""}) {
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("UPDATE pdp.device SET state = ? WHERE id = ?", state, id),
          state);
    }
  }

  /** L1 : un code émis ne peut être ni modifié ni supprimé par le rôle applicatif. */
  @Test
  void theApplicationRole_canNeitherUpdateNorDeleteARecoveryCode() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    codes.issue(id, T0);
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE pdp.recovery_code SET code_hash = 'x' WHERE device_id = ?", id));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM pdp.recovery_code WHERE device_id = ?", id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.recovery_code WHERE device_id = ?", Integer.class, id));
  }

  @Test
  void theApplicationRole_canIssueAndVerifyARecoveryCode() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    String code = codes.issue(id, T0);
    assertTrue(codes.matches(id, code));
    assertFalse(codes.matches(id, (code.charAt(0) == 'A' ? "B" : "A") + code.substring(1)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.recovery_code WHERE code_hash LIKE ?",
            Integer.class,
            "%" + code + "%"));
  }

  @Test
  void bindingFact_followsNonePendingActive_andAPendingKeyNeverSigns() {
    init();
    assertEquals(BindingState.NONE, registry.bindingState(acc));
    String first = registry.registerPending(acc, "cGs=", T0);
    assertEquals(BindingState.PENDING, registry.bindingState(acc));
    assertTrue(registry.findActive(acc).isEmpty(), "une clé EN ATTENTE ne signe rien (D4)");
    assertTrue(registry.activate(acc, first));
    assertEquals(BindingState.ACTIVE, registry.bindingState(acc));
    assertEquals(first, registry.findActive(acc).orElseThrow().id());
  }

  @Test
  void neverTwoActiveKeys_aSecondActivationIsAnOrdinaryFailure() {
    init();
    String first = registry.registerPending(acc, "cGs=", T0);
    assertTrue(registry.activate(acc, first));
    String second = registry.registerPending(acc, "cXM=", T0);
    assertFalse(registry.activate(acc, second), "l'index unique refuse une seconde clé ACTIVE");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.device WHERE account_id = ? AND state = 'ACTIVE'",
            Integer.class,
            acc));
  }

  @Test
  void atMostOnePendingKey_isEnforcedByTheDatabase() {
    init();
    registry.registerPending(acc, "cGs=", T0);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO pdp.device (id, account_id, public_key, state, enrolled_at) "
                    + "VALUES (gen_random_uuid()::text, ?, 'cXM=', 'PENDING', now())",
                acc),
        "la base, pas une convention, refuse une seconde clé EN ATTENTE");
  }

  @Test
  void registeringAgain_revokesThePreviousPendingKey_whichCanNoLongerBeActivated() {
    init();
    String lost = registry.registerPending(acc, "cGs=", T0);
    String retry = registry.registerPending(acc, "cXM=", T0);
    assertEquals(
        "REVOKED",
        jdbc.queryForObject("SELECT state FROM pdp.device WHERE id = ?", String.class, lost));
    assertFalse(registry.activate(acc, lost), "une clé EN ATTENTE remplacée n'est plus activable");
    assertTrue(registry.activate(acc, retry));
    assertEquals(retry, registry.findActive(acc).orElseThrow().id());
  }

  @Test
  void activationOfAnotherAccountsKey_changesNothing() {
    init();
    String id = registry.registerPending(acc, "cGs=", T0);
    assertFalse(registry.activate("acc-other-" + UUID.randomUUID(), id));
    assertEquals(BindingState.PENDING, registry.bindingState(acc));
  }
}
