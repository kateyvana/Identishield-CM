package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.audit.AuditLog;
import cm.identishield.pdp.audit.AuthorizedHistory;
import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.DeviceKey;
import cm.identishield.pdp.rules.Operation;
import cm.identishield.pdp.rules.RuleEngine;
import cm.identishield.pdp.rules.Score.Decision;
import cm.identishield.pdp.signature.NonceStore;
import cm.identishield.pdp.signature.SignatureVerifier;
import cm.identishield.pdp.trust.DecisionEnforcer;
import cm.identishield.pdp.trust.ProbationVeto;
import cm.identishield.pdp.trust.TrustStateMachine;
import cm.identishield.pdp.trust.TrustStateMachine.FreezeCause;
import cm.identishield.pdp.trust.TrustStateMachine.State;
import cm.identishield.pdp.trust.TrustStateMachine.Trust;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Chaîne de décision de /evaluate (carte A4) : idempotence → signature → état du compte → règles
 * (V1). Moteur de règles et veto de PROBATION RÉELS, lus dans config/ruleset.yaml ; l'état du
 * compte, l'historique et le journal sont simulés (la base réelle : EvaluateChainDbTest).
 *
 * <p>Exigences vérifiées :
 *
 * <p>@covers ES-04
 *
 * <p>@covers ES-05f
 *
 * <p>@covers EF-11
 *
 * <p>@covers ENF-03
 */
class EvaluateChainTest {

  private static final String ACC = "acc-a4";
  private static final String KNOWN = "677000456";
  private static final String NEW = "677000999";
  private static final String IMSI = "624010000000123";

  /** 10:00 UTC = 11:00 à Douala : de jour. */
  private static final Instant DAY = Instant.parse("2026-10-17T10:00:00Z");

  /** 01:00 UTC = 02:00 à Douala : de nuit. */
  private static final Instant NIGHT = Instant.parse("2026-10-17T01:00:00Z");

  private static final String NONCE = "0123456789abcdef0123456789abcdef";
  private static final long BALANCE = 1_000_000;

  private final RuleSet rs = TestRuleSet.load();
  private final long thrHigh = rs.integer("THR_HIGH.absoluteXaf");
  private final List<Map<String, Object>> journal = new ArrayList<>();
  private final AuditLog auditLog = mock(AuditLog.class);
  private final TrustStateMachine trust = mock(TrustStateMachine.class);
  private final AuthorizedHistory history = mock(AuthorizedHistory.class);
  private final DecisionEnforcer enforcer = mock(DecisionEnforcer.class);
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final NonceStore nonces = mock(NonceStore.class);
  private final KeyPair device;

  EvaluateChainTest() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    device = g.generateKeyPair();
    when(registry.findActive(ACC))
        .thenReturn(
            Optional.of(
                new DeviceKey(
                    "dk-a4",
                    ACC,
                    Base64.getEncoder().encodeToString(device.getPublic().getEncoded()))));
    org.mockito.Mockito.doAnswer(
            inv -> {
              journal.add(inv.getArgument(0));
              return null;
            })
        .when(auditLog)
        .append(any(), any());
    when(auditLog.findByDecisionId(anyString()))
        .thenAnswer(
            inv ->
                journal.stream()
                    .filter(p -> inv.getArgument(0).equals(p.get("decisionId")))
                    .findFirst());
    when(history.of(ACC))
        .thenReturn(List.of(new Operation(KNOWN, 1_000, true, DAY.minusSeconds(86_400))));
  }

  private EvaluateController controller(Instant now) {
    return new EvaluateController(
        auditLog,
        Clock.fixed(now, ZoneOffset.UTC),
        rs,
        new SignatureVerifier(registry, nonces),
        trust,
        history,
        new RuleEngine(rs),
        new ProbationVeto(rs),
        enforcer);
  }

  private void accountIs(State state, FreezeCause cause) {
    Trust t =
        new Trust(
            ACC,
            state,
            DAY,
            IMSI,
            IMSI,
            state == State.PROBATION ? DAY.plus(rs.carImsi()) : null,
            cause,
            cause == FreezeCause.FREEZE_DECISION ? DAY.plus(rs.ttlFreeze()) : null,
            null,
            state == State.PROBATION ? DAY.minusSeconds(60) : null,
            state == State.PROBATION ? BALANCE : null);
    when(trust.observed(eq(ACC), anyString(), any(), anyLong())).thenReturn(t);
  }

  private static EvaluateRequest req(
      String did, String channel, String type, long amount, String beneficiary, long balance) {
    return new EvaluateRequest(
        did,
        ACC,
        "677000123",
        IMSI,
        channel,
        type,
        amount,
        beneficiary,
        "2026-10-17T10:00:00Z",
        null,
        null,
        null,
        null,
        "NONE",
        balance,
        null);
  }

  private static String did(int n) {
    return String.format("5f1e7c1a-9d3b-4e6a-8c2f-%012d", n);
  }

  // ---- V1 : ordre des contrôles ----

  @Test
  void anInvalidSignature_isVetoedBeforeTheAccountStateIsEvenRead() throws Exception {
    accountIs(State.FROZEN, FreezeCause.NOT_ME);
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.getPrivate());
    s.update(("other|P2P|1|x|" + NONCE).getBytes(StandardCharsets.UTF_8));
    EvaluateRequest signed =
        new EvaluateRequest(
            did(1),
            ACC,
            "677000123",
            IMSI,
            "APP",
            "P2P",
            1_000,
            KNOWN,
            "2026-10-17T10:00:00Z",
            null,
            "dk-a4",
            Base64.getEncoder().encodeToString(s.sign()),
            NONCE,
            "SIGNATURE",
            BALANCE,
            null);
    EvaluateResponse r = controller(DAY).evaluate(signed);
    assertEquals("REJECTED", r.decision());
    assertEquals("SIGNATURE_INVALID", r.vetoApplied());
    verify(trust, never()).observed(anyString(), anyString(), any(), anyLong());
  }

  // ---- état du compte ----

  @Test
  void aFrozenAccount_rejectsAnOutflow_withAccountFrozen() {
    accountIs(State.FROZEN, FreezeCause.FREEZE_DECISION);
    EvaluateResponse r =
        controller(DAY).evaluate(req(did(2), "USSD", "P2P", 1_000, KNOWN, BALANCE));
    assertEquals("REJECTED", r.decision());
    assertEquals("ACCOUNT_FROZEN", r.vetoApplied());
    assertEquals(0, r.score());
    assertEquals(List.of(), r.triggeredRules());
    assertEquals("NONE", r.requiredAction());
  }

  @Test
  void aFrozenAccount_stillAcceptsACashIn() {
    accountIs(State.FROZEN, FreezeCause.NOT_ME);
    EvaluateResponse r =
        controller(DAY).evaluate(req(did(3), "USSD", "CASH_IN", 50_000, NEW, BALANCE));
    assertEquals("ALLOW", r.decision());
    assertEquals("NONE", r.vetoApplied());
  }

  @Test
  void inProbation_aNewBeneficiary_isFrozenByTheProbationVeto_andTheAccountIsFrozen() {
    accountIs(State.PROBATION, null);
    EvaluateResponse r = controller(DAY).evaluate(req(did(4), "USSD", "P2P", 1_000, NEW, BALANCE));
    assertEquals("FREEZE", r.decision());
    assertEquals("PROBATION_VIOLATION", r.vetoApplied());
    assertEquals(0, r.score());
    assertEquals(List.of(), r.triggeredRules());
    verify(enforcer, times(1)).apply(ACC, did(4), Decision.FREEZE);
  }

  @Test
  void anOutflowAboveTheBalance_isRejected_withInsufficientFunds() {
    accountIs(State.TRUSTED, null);
    EvaluateResponse r = controller(DAY).evaluate(req(did(5), "USSD", "P2P", 2_001, NEW, 2_000));
    assertEquals("REJECTED", r.decision());
    assertEquals("INSUFFICIENT_FUNDS", r.vetoApplied());
    verify(enforcer, never()).apply(anyString(), anyString(), any());
  }

  // ---- règles ----

  @Test
  void aStepUp_requiresDefer_onUssdAndOnTheApp_thePdpIgnoresTheChannel() {
    accountIs(State.TRUSTED, null);
    for (String channel : List.of("USSD", "APP")) {
      EvaluateResponse r =
          controller(DAY)
              .evaluate(req(did(channel.length()), channel, "P2P", thrHigh, NEW, BALANCE));
      assertEquals("STEP_UP", r.decision(), channel);
      assertEquals("DEFER", r.requiredAction(), channel);
      assertEquals("NONE", r.vetoApplied(), channel);
    }
    verify(enforcer, never()).apply(anyString(), anyString(), eq(Decision.FREEZE));
  }

  @Test
  void aFreezeByTheScore_freezesTheAccount_onceWithItsRules() {
    accountIs(State.TRUSTED, null);
    EvaluateResponse r =
        controller(NIGHT).evaluate(req(did(6), "USSD", "P2P", thrHigh, NEW, BALANCE));
    assertEquals("FREEZE", r.decision());
    assertEquals(
        List.of("NEW_BENEFICIARY_HIGH", "NIGHT"),
        r.triggeredRules().stream().map(TriggeredRule::ruleId).toList());
    verify(enforcer, times(1)).apply(ACC, did(6), Decision.FREEZE);
  }

  @Test
  void everyTriggeredRule_carriesAFactualExplanation_withoutAnyClientData() {
    accountIs(State.TRUSTED, null);
    EvaluateResponse r =
        controller(NIGHT).evaluate(req(did(7), "USSD", "P2P", thrHigh, NEW, BALANCE));
    assertFalse(r.triggeredRules().isEmpty());
    for (TriggeredRule t : r.triggeredRules()) {
      assertFalse(t.explanation().isBlank(), t.ruleId());
      assertFalse(t.explanation().matches(".*\\d{3,}.*"), "aucun numéro ni montant : " + t);
      assertFalse(t.explanation().contains(NEW), t.ruleId());
    }
  }

  @Test
  void everyDecision_carriesTheRuleSetVersion_andExactlyOneAuditEntry() {
    accountIs(State.TRUSTED, null);
    EvaluateResponse r =
        controller(DAY).evaluate(req(did(8), "USSD", "P2P", 1_000, KNOWN, BALANCE));
    assertEquals(rs.version(), r.ruleSetVersion());
    assertEquals(1, journal.stream().filter(p -> did(8).equals(p.get("decisionId"))).count());
  }

  // ---- V6 : le rejeu ne produit jamais d'effet de bord ----

  @Test
  void aReplayedFreeze_returnsTheSameDecision_withoutFreezingAgain() {
    accountIs(State.PROBATION, null);
    EvaluateController c = controller(DAY);
    EvaluateResponse first = c.evaluate(req(did(9), "USSD", "P2P", 1_000, NEW, BALANCE));
    EvaluateResponse replay = c.evaluate(req(did(9), "USSD", "P2P", 1_000, NEW, BALANCE));
    assertEquals("FREEZE", first.decision());
    assertEquals(first.decision(), replay.decision());
    assertEquals(first.vetoApplied(), replay.vetoApplied());
    verify(enforcer, times(1)).apply(ACC, did(9), Decision.FREEZE);
    verify(trust, times(1)).observed(eq(ACC), anyString(), any(), anyLong());
    verify(auditLog, times(1)).append(any(), any());
  }

  // ---- limite temporaire du mois 4 (Q6) ----

  @Test
  void limiteMois4_anUnsignedAppOperation_isNotRefused() {
    // Au mois 4, une opération APP non signée n'est pas refusée ; le seul contrôle est que
    // /evaluate n'est joignable qu'après une poignée de main mTLS réussie (OB-5). ES-02 sera
    // imposée dès que le couloir B fournit la route mobile signée.
    accountIs(State.TRUSTED, null);
    EvaluateResponse r =
        controller(DAY).evaluate(req(did(10), "APP", "P2P", 1_000, KNOWN, BALANCE));
    assertEquals("ALLOW", r.decision());
    assertTrue(r.triggeredRules().isEmpty());
  }
}
