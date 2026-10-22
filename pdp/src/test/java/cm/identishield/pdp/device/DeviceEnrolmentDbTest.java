package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceRegistry.BindingState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
