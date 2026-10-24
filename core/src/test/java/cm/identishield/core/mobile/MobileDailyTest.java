package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.MobileLedger.AccountView;
import cm.identishield.core.mobile.MobileLedger.TransactionView;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import cm.identishield.core.transfer.OperationType;
import cm.identishield.core.transfer.Proof;
import cm.identishield.core.transfer.TransferResult;
import cm.identishield.core.transfer.TransferService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Quotidien côté cœur (carte B6) : le nonce vient du PDP ; l'annulation n'a lieu que sur VALID du
 * PDP, sur la chaîne canonique exacte, avec le nonce consommé à part ; le bénéficiaire est désigné
 * par son MSISDN, la chaîne que le PDP recalcule.
 *
 * <p>@covers ES-10
 *
 * <p>@covers ES-20
 */
class MobileDailyTest {

  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000123");
  private static final String MSISDN = "677000123";
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";
  private static final String NONCE = "9f86d081884c7d659a2feaa0c55ad015";
  private static final String TRANSFER = "11111111-2222-4333-8444-555555555555";
  private static final LiveSession SESSION = new LiveSession(ACCOUNT, DEVICE);

  private static final String PIN = "4096";
  private static final String ORIGIN = "100.64.0.9";
  private static final String BENEFICIARY = "677001101";

  private final MobileLedger ledger = mock(MobileLedger.class);
  private final DeviceClient device = mock(DeviceClient.class);
  private final TransferService transfers = mock(TransferService.class);
  private final PinStore pins = mock(PinStore.class);
  private final PinHasher hasher = new PinHasher(new PinProperties(1_000, 16, 256));
  private final PinAttempts attempts =
      new PinAttempts(
          new cm.identishield.core.time.VirtualClock(Instant.parse("2026-10-24T08:00:00Z")),
          2,
          java.time.Duration.ofMinutes(15));
  private final MobileDaily daily =
      new MobileDaily(ledger, device, transfers, pins, hasher, attempts);

  private void pinKnown() {
    when(pins.find(MSISDN))
        .thenReturn(Optional.of(new PinStore.AccountPin(ACCOUNT, hasher.protect(PIN))));
  }

  private void beneficiaryKnown() {
    when(ledger.findByMsisdn(BENEFICIARY))
        .thenReturn(
            Optional.of(new AccountView(UUID.randomUUID(), BENEFICIARY, "Aline MBALLA", 0)));
  }

  private void pdpDecides(TransferResult result) {
    when(transfers.operate(
            eq(MSISDN),
            eq(OperationType.P2P),
            eq(5_000L),
            eq(BENEFICIARY),
            eq("APP"),
            any(Proof.class)))
        .thenReturn(result);
  }

  private Answer send(String signature) {
    return daily.transfer(SESSION, 5_000, BENEFICIARY, NONCE, signature, PIN, ORIGIN);
  }

  @Test
  void transfer_wrongPin_isRefused_countedForTheOrigin_andThePdpIsNeverCalled() {
    knownAccount();
    pinKnown();
    assertEquals(
        401, daily.transfer(SESSION, 5_000, BENEFICIARY, NONCE, "c2ln", "0000", ORIGIN).status());
    assertEquals(
        401, daily.transfer(SESSION, 5_000, BENEFICIARY, NONCE, "c2ln", "0000", ORIGIN).status());
    Answer third = daily.transfer(SESSION, 5_000, BENEFICIARY, NONCE, "c2ln", PIN, ORIGIN);
    assertEquals(429, third.status(), "origine bloquée AVANT tout calcul, même PIN juste");
    assertEquals(Map.of("reason", "THROTTLED"), third.body());
    assertTrue(third.throttledSeconds() > 0);
    verify(transfers, never())
        .operate(
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            anyString(),
            any());
  }

  @Test
  void transfer_unknownBeneficiary_is404_insufficientFunds_is409_bothWithoutThePdp() {
    knownAccount();
    pinKnown();
    when(ledger.findByMsisdn(BENEFICIARY)).thenReturn(Optional.empty());
    assertEquals(404, send("c2ln").status());
    beneficiaryKnown();
    Answer a = daily.transfer(SESSION, 12_001, BENEFICIARY, NONCE, "c2ln", PIN, ORIGIN);
    assertEquals(409, a.status());
    assertEquals(Map.of("reason", "INSUFFICIENT_FUNDS"), a.body());
    verify(transfers, never())
        .operate(
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            anyString(),
            any());
  }

  @Test
  void transfer_allow_isExecuted_withTheProofOfTheSessionDevice_andTheBalanceAfter() {
    knownAccount();
    pinKnown();
    beneficiaryKnown();
    pdpDecides(TransferResult.executed(TRANSFER).withDecision("ALLOW"));
    Answer a = send("c2ln");
    assertEquals(200, a.status());
    assertEquals(
        Map.of(
            "transferId",
            TRANSFER,
            "decision",
            "ALLOW",
            "status",
            "EXECUTED",
            "balanceXaf",
            12_000L),
        a.body());
    org.mockito.ArgumentCaptor<Proof> proof = org.mockito.ArgumentCaptor.forClass(Proof.class);
    verify(transfers)
        .operate(
            eq(MSISDN),
            eq(OperationType.P2P),
            eq(5_000L),
            eq(BENEFICIARY),
            eq("APP"),
            proof.capture());
    assertEquals(new Proof(DEVICE, "c2ln", NONCE), proof.getValue());
  }

  @Test
  void transfer_withoutSignature_stillGoesToThePdpAsSIGNATURE_whichRejectsIt() {
    knownAccount();
    pinKnown();
    beneficiaryKnown();
    pdpDecides(
        TransferResult.rejected(TRANSFER, "decision=REJECTED", "SIGNATURE_INVALID")
            .withDecision("REJECTED"));
    Answer a = send(null);
    assertEquals(200, a.status());
    assertEquals("REJECTED", a.body().get("decision"));
    assertEquals("REJECTED", a.body().get("status"));
    assertEquals("DECISION_REJECTED", a.body().get("reason"));
    org.mockito.ArgumentCaptor<Proof> proof = org.mockito.ArgumentCaptor.forClass(Proof.class);
    verify(transfers)
        .operate(
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.anyLong(),
            anyString(),
            anyString(),
            proof.capture());
    assertEquals(
        new Proof(DEVICE, null, NONCE),
        proof.getValue(),
        "jamais proofType NONE : la preuve part, signature nulle");
  }

  @Test
  void transfer_stepUp_isDeferred_withTheDueDateReadFromTheLedger_neverFromDetail() {
    knownAccount();
    pinKnown();
    beneficiaryKnown();
    Instant due = Instant.parse("2026-10-24T12:00:00Z");
    pdpDecides(TransferResult.deferred(TRANSFER, due).withDecision("STEP_UP"));
    when(ledger.deferred(ACCOUNT))
        .thenReturn(List.of(new MobileLedger.DeferredView(TRANSFER, 5_000, BENEFICIARY, due)));
    Answer a = send("c2ln");
    assertEquals(200, a.status());
    assertEquals("STEP_UP", a.body().get("decision"));
    assertEquals("DEFERRED", a.body().get("status"));
    assertEquals("DECISION_DEFERRED", a.body().get("reason"));
    assertEquals("2026-10-24T12:00:00Z", a.body().get("executeAt"));
  }

  @Test
  void transfer_freeze_isFrozen_frozenAccount_is403_allowRefusedAtCommit_is409() {
    knownAccount();
    pinKnown();
    beneficiaryKnown();
    pdpDecides(TransferResult.rejected(TRANSFER, "decision=FREEZE", "NONE").withDecision("FREEZE"));
    Answer frozen = send("c2ln");
    assertEquals(200, frozen.status());
    assertEquals("FREEZE", frozen.body().get("decision"));
    assertEquals("FROZEN", frozen.body().get("status"));
    assertEquals("DECISION_FROZEN", frozen.body().get("reason"));

    pdpDecides(
        TransferResult.rejected(TRANSFER, "decision=REJECTED", "ACCOUNT_FROZEN")
            .withDecision("REJECTED"));
    Answer account = send("c2ln");
    assertEquals(403, account.status());
    assertEquals(Map.of("reason", "ACCOUNT_FROZEN"), account.body());

    pdpDecides(TransferResult.rejected(TRANSFER, "solde insuffisant").withDecision("ALLOW"));
    Answer race = send("c2ln");
    assertEquals(409, race.status());
    assertEquals(Map.of("reason", "INSUFFICIENT_FUNDS"), race.body());
  }

  private void knownAccount() {
    when(ledger.find(ACCOUNT))
        .thenReturn(Optional.of(new AccountView(ACCOUNT, MSISDN, "Hervé ESSOMBA", 12_000)));
  }

  private void pdpVerdict(String verdict) {
    when(device.verify(
            ACCOUNT.toString(),
            DEVICE,
            ACCOUNT + "|CANCEL|" + TRANSFER + "|" + NONCE,
            "c2ln",
            NONCE))
        .thenReturn(new Reply(200, Map.of("verdict", verdict)));
  }

  @Test
  void nonce_comesFromThePdp_forTheAccountOfTheSession() {
    when(device.nonceForAccount(ACCOUNT.toString()))
        .thenReturn(new Reply(200, Map.of("nonce", NONCE, "accountId", ACCOUNT.toString())));
    Answer a = daily.nonce(SESSION);
    assertEquals(200, a.status());
    assertEquals(Map.of("nonce", NONCE), a.body());
  }

  @Test
  void nonce_pdpUnreachable_isTransient503() {
    when(device.nonceForAccount(anyString())).thenReturn(new Reply(Reply.UNREACHABLE, Map.of()));
    Answer a = daily.nonce(SESSION);
    assertEquals(503, a.status());
    assertEquals(Map.of("reason", "SERVICE_UNAVAILABLE"), a.body());
  }

  @Test
  void account_stateComesFromThePdp_copiedNotInterpreted_balanceReadAfterSettlement() {
    knownAccount();
    when(device.trust(ACCOUNT.toString()))
        .thenReturn(
            new Reply(
                200,
                Map.of(
                    "state",
                    "PROBATION",
                    "since",
                    "2026-10-24T07:00:00Z",
                    "probationUntil",
                    "2026-10-27T07:00:00Z")));
    Instant executeAt = Instant.parse("2026-10-24T12:00:00Z");
    when(ledger.deferred(ACCOUNT))
        .thenReturn(
            List.of(new MobileLedger.DeferredView(TRANSFER, 5_000, "677001101", executeAt)));
    Answer a = daily.account(SESSION);
    verify(transfers).settleDeferred(MSISDN);
    assertEquals(200, a.status());
    assertEquals(
        Map.of(
            "accountId",
            ACCOUNT.toString(),
            "msisdn",
            MSISDN,
            "holderName",
            "Hervé ESSOMBA",
            "balanceXaf",
            12_000L,
            "state",
            "PROBATION",
            "deferredOperations",
            List.of(
                Map.of(
                    "transferId",
                    TRANSFER,
                    "amountXaf",
                    5_000L,
                    "beneficiaryMsisdn",
                    "677001101",
                    "executeAt",
                    "2026-10-24T12:00:00Z"))),
        a.body());
  }

  @Test
  void account_neverSeenByThePdp_isTrusted_theStateThePdpGivesAnAccountWithoutHistory() {
    knownAccount();
    when(device.trust(ACCOUNT.toString()))
        .thenReturn(new Reply(404, Map.of("code", "ACCOUNT_UNKNOWN", "message", "never seen")));
    Answer a = daily.account(SESSION);
    assertEquals(200, a.status());
    assertEquals("TRUSTED", a.body().get("state"));
    assertEquals(List.of(), a.body().get("deferredOperations"));
  }

  @Test
  void account_pdpUnreachable_orStateMissing_isTransient503_neverAGuessedState() {
    knownAccount();
    when(device.trust(ACCOUNT.toString())).thenReturn(new Reply(Reply.UNREACHABLE, Map.of()));
    assertEquals(503, daily.account(SESSION).status());
    when(device.trust(ACCOUNT.toString())).thenReturn(new Reply(200, Map.of("since", "x")));
    assertEquals(503, daily.account(SESSION).status());
    when(device.trust(ACCOUNT.toString())).thenReturn(new Reply(500, Map.of()));
    assertEquals(503, daily.account(SESSION).status());
  }

  @Test
  void account_sessionOfAnUnknownAccount_is401_andThePdpIsNotAsked() {
    when(ledger.find(ACCOUNT)).thenReturn(Optional.empty());
    assertEquals(401, daily.account(SESSION).status());
    verify(device, never()).trust(anyString());
  }

  @Test
  void transactions_settleDueDeferredFirst_thenListNewestFirst_withoutNullNames() {
    knownAccount();
    Instant at = Instant.parse("2026-10-24T08:00:00Z");
    when(ledger.history(ACCOUNT, MSISDN))
        .thenReturn(
            List.of(
                new TransactionView(TRANSFER, "OUT", 5_000, "677001101", null, "DEFERRED", at),
                new TransactionView(
                    "22222222-2222-4333-8444-555555555555",
                    "IN",
                    1_500,
                    "677000200",
                    "Aline MBALLA",
                    "EXECUTED",
                    at.minusSeconds(60))));
    Answer a = daily.transactions(SESSION);
    verify(transfers).settleDeferred(MSISDN);
    assertEquals(200, a.status());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) a.body().get("items");
    assertEquals(2, items.size());
    assertEquals(
        Map.of(
            "transferId", TRANSFER,
            "direction", "OUT",
            "amountXaf", 5_000L,
            "counterpartyMsisdn", "677001101",
            "status", "DEFERRED",
            "at", "2026-10-24T08:00:00Z"),
        items.get(0));
    assertEquals("Aline MBALLA", items.get(1).get("counterpartyName"));
  }

  @Test
  void transactions_sessionOfAnUnknownAccount_isRefusedLikeAnyBadToken() {
    when(ledger.find(ACCOUNT)).thenReturn(Optional.empty());
    Answer a = daily.transactions(SESSION);
    assertEquals(401, a.status());
    assertEquals(Map.of("reason", "CREDENTIALS_INVALID"), a.body());
    verify(transfers, never()).settleDeferred(anyString());
  }

  @Test
  void holder_beneficiaryIdIsTheMsisdn_theStringThePdpRecomputes() {
    when(ledger.findByMsisdn("677001101"))
        .thenReturn(
            Optional.of(new AccountView(UUID.randomUUID(), "677001101", "Aline MBALLA", 0)));
    when(ledger.everPaid(ACCOUNT, "677001101")).thenReturn(false);
    Answer a = daily.holder(SESSION, "677001101");
    assertEquals(200, a.status());
    assertEquals(
        Map.of(
            "msisdn", "677001101",
            "beneficiaryId", "677001101",
            "holderName", "Aline MBALLA",
            "newBeneficiary", true),
        a.body());
  }

  @Test
  void holder_withoutRegisteredName_isAnEmptyName_notA404_andKnownBeneficiaryHasNoBadge() {
    when(ledger.findByMsisdn("677001101"))
        .thenReturn(Optional.of(new AccountView(UUID.randomUUID(), "677001101", null, 0)));
    when(ledger.everPaid(ACCOUNT, "677001101")).thenReturn(true);
    Answer a = daily.holder(SESSION, "677001101");
    assertEquals(200, a.status());
    assertEquals("", a.body().get("holderName"));
    assertEquals(false, a.body().get("newBeneficiary"));
  }

  @Test
  void holder_unknownNumber_is404() {
    when(ledger.findByMsisdn("677001101")).thenReturn(Optional.empty());
    Answer a = daily.holder(SESSION, "677001101");
    assertEquals(404, a.status());
    assertEquals(Map.of("reason", "REQUEST_INVALID"), a.body());
  }

  @Test
  void cancel_valid_cancelsTheDeferredOperationOfTheSessionAccount() {
    knownAccount();
    pdpVerdict("VALID");
    when(transfers.cancelDeferred(MSISDN, TRANSFER)).thenReturn(true);
    Answer a = daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    assertEquals(200, a.status());
    assertEquals(Map.of("transferId", TRANSFER, "status", "CANCELLED"), a.body());
  }

  @Test
  void cancel_valid_butNothingCancellable_is404_andTheNonceIsSpentAnyway() {
    knownAccount();
    pdpVerdict("VALID");
    when(transfers.cancelDeferred(MSISDN, TRANSFER)).thenReturn(false);
    Answer a = daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    assertEquals(404, a.status());
    assertEquals(Map.of("reason", "REQUEST_INVALID"), a.body());
  }

  @Test
  void cancel_invalidSignature_isRefused_andNothingIsCancelled() {
    knownAccount();
    pdpVerdict("INVALID");
    Answer a = daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    assertEquals(403, a.status());
    assertEquals(Map.of("reason", "DECISION_REJECTED"), a.body());
    verify(transfers, never()).cancelDeferred(anyString(), anyString());
  }

  @Test
  void cancel_revokedKey_namesIt_andNothingIsCancelled() {
    knownAccount();
    pdpVerdict("REVOKED");
    Answer a = daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    assertEquals(403, a.status());
    assertEquals(Map.of("reason", "KEY_REVOKED"), a.body());
    verify(transfers, never()).cancelDeferred(anyString(), anyString());
  }

  @Test
  void cancel_pdpUnreachable_isTransient503_andNothingIsCancelled() {
    knownAccount();
    when(device.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new Reply(Reply.UNREACHABLE, Map.of()));
    Answer a = daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    assertEquals(503, a.status());
    verify(transfers, never()).cancelDeferred(anyString(), anyString());
  }

  @Test
  void cancel_neverTrustsTheNonceAlone_theSignedChainIsPassedToThePdp() {
    knownAccount();
    when(device.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new Reply(200, Map.of("verdict", "INVALID")));
    daily.cancel(SESSION, TRANSFER, NONCE, "c2ln");
    verify(device)
        .verify(
            ACCOUNT.toString(),
            DEVICE,
            ACCOUNT + "|CANCEL|" + TRANSFER + "|" + NONCE,
            "c2ln",
            NONCE);
    verify(device, never()).verify(any(), any(), any(), any());
  }

  @Test
  void alerts_areEmptyUntilB5_neverFabricated() {
    Answer a = daily.alerts(SESSION);
    assertEquals(200, a.status());
    assertEquals(Map.of("items", List.of()), a.body());
    assertFalse(a.retryAfter());
  }
}
