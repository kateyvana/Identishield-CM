package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.account.Account;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.mode.LayerMode;
import cm.identishield.core.notification.NotificationService;
import cm.identishield.core.pdp.EvaluateOutcome;
import cm.identishield.core.pdp.PdpClient;
import cm.identishield.core.transaction.PendingTransaction;
import cm.identishield.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Carte B6 (préparation côté cœur) : la preuve de l'appareil lié part au PDP telle quelle, et le
 * veto du PDP remonte tel quel dans l'issue. Le cœur ne vérifie ni ne réinterprète rien.
 *
 * <p>@covers ES-02 (le cœur ne contourne jamais le veto de signature : une opération APP part
 * toujours en {@code proofType=SIGNATURE}, même sans signature).
 */
class TransferProofTest {

  private static final String FROM = "677000001";
  private static final String BENEF = "677000002";
  private static final String KEY = "dev-1";
  private static final String NONCE = "9f86d081884c7d659a2feaa0c55ad015";

  private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
  private final TransactionRepository transactions = Mockito.mock(TransactionRepository.class);
  private final PdpClient pdp = Mockito.mock(PdpClient.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-0.1.0", Map.of("CAP_FAILCLOSED", Map.of("xaf", 25000)));
  private final HlrService hlr = Mockito.mock(HlrService.class);
  private final NotificationService notifications = Mockito.mock(NotificationService.class);
  private final LayerMode layerMode = Mockito.mock(LayerMode.class);
  private final ObjectMapper json = new ObjectMapper();

  private final TransferService service =
      new TransferService(
          accounts,
          transactions,
          pdp,
          ruleSet,
          Clock.fixed(Instant.parse("2026-10-24T20:00:00Z"), ZoneOffset.UTC),
          json,
          hlr,
          notifications,
          layerMode);

  private final Account sender = new Account(UUID.randomUUID(), FROM, 1_000_000);
  private final PendingTransaction pending = new PendingTransaction(UUID.randomUUID(), "did-9");

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(FROM)).thenReturn(Optional.of(sender));
    when(accounts.findByMsisdn(BENEF)).thenReturn(Optional.empty());
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(pending);
    when(hlr.currentImsi(FROM)).thenReturn(Optional.of("624010000000001"));
  }

  private JsonNode sentRequest() throws Exception {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(pdp).evaluate(body.capture());
    return json.readTree(body.getValue());
  }

  @Test
  void withAProof_theRequestCarriesSignatureAndTheThreeFields_verbatim() throws Exception {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-9"));

    TransferResult r =
        service.operate(
            FROM, OperationType.P2P, 25_000, BENEF, "APP", new Proof(KEY, "c2ln", NONCE));

    assertEquals(TransferResult.State.EXECUTED, r.state());
    assertEquals(TransferResult.NO_VETO, r.vetoApplied());
    assertEquals("ALLOW", r.decision());
    JsonNode req = sentRequest();
    assertEquals("SIGNATURE", req.get("proofType").asText());
    assertEquals(KEY, req.get("deviceKeyId").asText());
    assertEquals("c2ln", req.get("signature").asText());
    assertEquals(NONCE, req.get("nonce").asText());
    assertEquals("APP", req.get("channel").asText());
  }

  @Test
  void withoutAProof_theRequestIsProofTypeNone_asBefore() throws Exception {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-9"));

    service.operate(FROM, OperationType.P2P, 25_000, BENEF, "USSD");

    JsonNode req = sentRequest();
    assertEquals("NONE", req.get("proofType").asText());
    assertFalse(req.has("deviceKeyId"));
    assertFalse(req.has("signature"));
    assertFalse(req.has("nonce"));
  }

  @Test
  void aMissingSignature_stillGoesAsSignature_withANullSignature_neverAsNone() throws Exception {
    // Le PDP rend alors REJECTED, veto SIGNATURE_INVALID (ES-02) et écrit l'audit : c'est lui qui
    // refuse, pas le cœur. Envoyer NONE ferait accepter une opération APP non signée.
    when(pdp.evaluate(anyString()))
        .thenReturn(EvaluateOutcome.of("REJECTED", "did-9", "SIGNATURE_INVALID"));

    TransferResult r =
        service.operate(FROM, OperationType.P2P, 25_000, BENEF, "APP", new Proof(KEY, null, NONCE));

    JsonNode req = sentRequest();
    assertEquals("SIGNATURE", req.get("proofType").asText());
    assertTrue(req.has("signature") && req.get("signature").isNull());
    assertEquals(TransferResult.State.REJECTED, r.state());
    assertEquals("SIGNATURE_INVALID", r.vetoApplied());
    verify(transactions).markRejected(pending.id());
    verify(accounts, never()).debit(any(), anyLong());
  }

  @Test
  void aFrozenAccount_rejectedByThePdp_surfacesTheVeto_unchanged() {
    when(pdp.evaluate(anyString()))
        .thenReturn(EvaluateOutcome.of("REJECTED", "did-9", "ACCOUNT_FROZEN"));

    TransferResult r =
        service.operate(
            FROM, OperationType.P2P, 25_000, BENEF, "APP", new Proof(KEY, "c2ln", NONCE));

    assertEquals(TransferResult.State.REJECTED, r.state());
    assertEquals("ACCOUNT_FROZEN", r.vetoApplied());
    assertEquals("REJECTED", r.decision());
    assertEquals("decision=REJECTED", r.detail());
  }

  @Test
  void aScoreRejection_hasNoVeto_andOlderOutcomesStayNone() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("FREEZE", "did-9"));

    TransferResult r = service.operate(FROM, OperationType.P2P, 25_000, BENEF, "USSD");

    assertEquals(TransferResult.State.REJECTED, r.state());
    assertEquals(TransferResult.NO_VETO, r.vetoApplied());
    assertEquals("FREEZE", r.decision(), "gel décidé sur cette opération : FREEZE, pas REJECTED");
    assertNull(TransferResult.executed("x").decision(), "aucune décision rendue : null");
    assertNull(TransferResult.executed("x").detail());
    assertEquals(TransferResult.NO_VETO, TransferResult.executed("x").vetoApplied());
  }
}
