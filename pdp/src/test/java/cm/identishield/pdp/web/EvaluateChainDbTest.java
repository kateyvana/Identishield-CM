package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.audit.AuthorizedHistory;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.rules.RuleEngine;
import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.time.VirtualClock;
import cm.identishield.pdp.trust.DecisionEnforcer;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La chaîne complète de /evaluate (carte A4) sur une VRAIE base (init.sql, rôle isc_pdp_app) :
 * journal d'audit, historique relu dans ce journal, machine à états, moteur, veto et application
 * des décisions réels. Ignorée sans base ; tools/verify_trust_state.sh la lance dans la VM.
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-01b
 *
 * <p>@covers ES-04
 *
 * <p>@covers ENF-03
 *
 * <p>@covers AC-02
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class EvaluateChainDbTest {

  private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
  private static final String VICTIM_IMSI = "624010000000123";
  private static final String ATTACKER_IMSI = "624019999999000";
  private static final String ATTACKER = "677009000";
  private static final String KNOWN = "677000456";
  private static final long BALANCE = 80_000;
  private static final AtomicInteger NEXT = new AtomicInteger();

  private static JdbcTemplate jdbc;
  private static DataSourceTransactionManager transactions;

  private final RuleSet rs = TestRuleSet.load();
  private final long thrHigh = rs.integer("THR_HIGH.absoluteXaf");
  private final VirtualClock clock = new VirtualClock(T0);
  private final String acc = "acc-a4-" + UUID.randomUUID();
  private TrustStateMachine trust;
  private EvaluateController controller;

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
  void chain() {
    AuditLog audit = new AuditLog(jdbc, new ObjectMapper());
    trust = new TrustStateMachine(jdbc, transactions, audit, clock, rs);
    controller =
        new EvaluateController(
            audit,
            clock,
            rs,
            new SignatureVerifier(mock(DeviceRegistry.class), mock(NonceStore.class)),
            trust,
            new AuthorizedHistory(jdbc),
            new RuleEngine(rs),
            new ProbationVeto(rs),
            new DecisionEnforcer(trust));
  }

  private static String did() {
    return String.format("7a3e9b1c-2d4f-4a6b-8c1d-%012d", NEXT.incrementAndGet() + 9_000_000);
  }

  private EvaluateResponse evaluate(
      String did, String imsi, Instant imsiChangedAt, String to, long amount, long balance) {
    return controller.evaluate(
        new EvaluateRequest(
            did,
            acc,
            "677000123",
            imsi,
            "USSD",
            "P2P",
            amount,
            to,
            clock.instant().toString(),
            null,
            null,
            null,
            null,
            "NONE",
            balance,
            imsiChangedAt == null ? null : imsiChangedAt.toString()));
  }

  private List<String> ruleIds(EvaluateResponse r) {
    return r.triggeredRules().stream().map(TriggeredRule::ruleId).toList();
  }

  private int transitions(String event) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pdp.trust_transition WHERE account_id = ? AND event = ?",
        Integer.class,
        acc,
        event);
  }

  // ---- AC-02 sur base neuve (Q3, Q4) ----

  @Test
  void ac02_onAFreshBase_theFirstRequestAfterASimSwap_isFrozen() {
    Instant swap = T0.minus(Duration.ofHours(1));
    EvaluateResponse r = evaluate(did(), ATTACKER_IMSI, swap, ATTACKER, BALANCE, BALANCE);
    assertEquals("FREEZE", r.decision());
    assertEquals("PROBATION_VIOLATION", r.vetoApplied());
    Trust t = trust.current(acc);
    assertEquals(State.FROZEN, t.state());
    assertEquals(swap.plus(rs.carImsi()), t.probationUntil()); // échéance d'origine conservée
    assertEquals(swap, t.probationEnteredAt());
    assertEquals(BALANCE, t.probationEntryBalanceXaf());
    // Puis l'attaquant réessaie : compte gelé, rien ne sort.
    EvaluateResponse again = evaluate(did(), ATTACKER_IMSI, swap, ATTACKER, 1_000, BALANCE);
    assertEquals("REJECTED", again.decision());
    assertEquals("ACCOUNT_FROZEN", again.vetoApplied());
  }

  @Test
  void aNeverSeenAccount_whoseSimChangedLongAgo_isTrusted_withTheCurrentImsiAsReference() {
    Instant oldSwap = T0.minus(rs.carImsi()).minusSeconds(1);
    EvaluateResponse r = evaluate(did(), VICTIM_IMSI, oldSwap, KNOWN, 1_000, BALANCE);
    assertEquals("ALLOW", r.decision());
    Trust t = trust.current(acc);
    assertEquals(State.TRUSTED, t.state());
    assertEquals(VICTIM_IMSI, t.referenceImsi());
  }

  @Test
  void aSimSwapOnAKnownAccount_opensProbation_withTheBalanceFrozenAtEntry() {
    evaluate(did(), VICTIM_IMSI, null, KNOWN, 1_000, BALANCE);
    clock.advance(Duration.ofHours(2));
    Instant swap = clock.instant().minusSeconds(600);
    EvaluateResponse r = evaluate(did(), ATTACKER_IMSI, swap, KNOWN, 1_000, 50_000);
    assertEquals("ALLOW", r.decision()); // bénéficiaire connu, sous le plafond
    Trust t = trust.current(acc);
    assertEquals(State.PROBATION, t.state());
    assertEquals(swap, t.probationEnteredAt());
    assertEquals(50_000L, t.probationEntryBalanceXaf());
  }

  // ---- V3 et Q5 : jamais de blanchiment d'un bénéficiaire ----

  @Test
  void aRefusedAttempt_neverMakesItsBeneficiaryKnown() {
    evaluate(did(), VICTIM_IMSI, null, KNOWN, 1_000, BALANCE);
    EvaluateResponse first = evaluate(did(), VICTIM_IMSI, null, ATTACKER, thrHigh, BALANCE);
    assertEquals("STEP_UP", first.decision());
    EvaluateResponse second = evaluate(did(), VICTIM_IMSI, null, ATTACKER, 1_000, BALANCE);
    assertTrue(ruleIds(second).contains("NEW_BENEFICIARY"), ruleIds(second).toString());
  }

  @Test
  void anAttemptAboveTheBalance_isRejected_andNeverMakesItsBeneficiaryKnown() {
    evaluate(did(), VICTIM_IMSI, null, KNOWN, 1_000, BALANCE);
    EvaluateResponse first = evaluate(did(), VICTIM_IMSI, null, ATTACKER, BALANCE + 1, BALANCE);
    assertEquals("REJECTED", first.decision());
    assertEquals("INSUFFICIENT_FUNDS", first.vetoApplied());
    EvaluateResponse second = evaluate(did(), VICTIM_IMSI, null, ATTACKER, 1_000, BALANCE);
    assertTrue(ruleIds(second).contains("NEW_BENEFICIARY"), ruleIds(second).toString());
  }

  @Test
  void anAllowedOperation_doesMakeItsBeneficiaryKnown() {
    evaluate(did(), VICTIM_IMSI, null, KNOWN, 1_000, BALANCE);
    EvaluateResponse first = evaluate(did(), VICTIM_IMSI, null, ATTACKER, 1_000, BALANCE);
    assertEquals(List.of("NEW_BENEFICIARY"), ruleIds(first));
    EvaluateResponse second = evaluate(did(), VICTIM_IMSI, null, ATTACKER, 1_000, BALANCE);
    assertEquals(List.of(), ruleIds(second));
  }

  // ---- V6 : le rejeu ne produit jamais d'effet de bord ----

  @Test
  void aReplayedFreeze_neitherFreezesAgain_norWritesASecondAuditEntry() {
    String d = did();
    Instant swap = T0.minus(Duration.ofHours(1));
    EvaluateResponse first = evaluate(d, ATTACKER_IMSI, swap, ATTACKER, BALANCE, BALANCE);
    EvaluateResponse replay = evaluate(d, ATTACKER_IMSI, swap, ATTACKER, BALANCE, BALANCE);
    assertEquals("FREEZE", first.decision());
    assertEquals(first.decision(), replay.decision());
    assertEquals(1, transitions("TRUST_FROZEN"));
    assertEquals(1, transitions("TRUST_PROBATION_ENTERED"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pdp.audit_entry WHERE payload ->> 'decisionId' = ?",
            Integer.class,
            d));
  }

  // ---- schéma : contraintes lisibles (V4) ----

  @Test
  void probationWithoutItsEntryBalanceOrInstant_isRefusedByTheDatabase() {
    evaluate(did(), VICTIM_IMSI, null, KNOWN, 1_000, BALANCE);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE pdp.trust_state SET state = 'PROBATION', probation_until = ?::timestamptz"
                    + " WHERE account_id = ?",
                T0.plus(rs.carImsi()).toString(),
                acc));
  }
}
