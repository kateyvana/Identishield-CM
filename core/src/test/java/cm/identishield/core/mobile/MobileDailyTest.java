package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.MobileLedger.AccountView;
import cm.identishield.core.mobile.MobileLedger.TransactionView;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
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

  private final MobileLedger ledger = mock(MobileLedger.class);
  private final DeviceClient device = mock(DeviceClient.class);
  private final TransferService transfers = mock(TransferService.class);
  private final MobileDaily daily = new MobileDaily(ledger, device, transfers);

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
