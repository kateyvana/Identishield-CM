package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.account.Account;
import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrReassignment;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Ce que le cœur envoie à /evaluate (carte A4) : le solde courant du compte (V4) et la date du
 * dernier changement d'IMSI, lue dans l'historique HLR immuable (Q4, EF-08, ADR-032) ; absente
 * quand le numéro n'a jamais été réattribué. Le motif d'une réattribution n'est jamais transmis
 * (ES-16b).
 *
 * <p>@covers EF-08
 */
class EvaluateRequestContentTest {

  private static final String FROM = "677000001";
  private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

  private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
  private final TransactionRepository transactions = Mockito.mock(TransactionRepository.class);
  private final PdpClient pdp = Mockito.mock(PdpClient.class);
  private final HlrService hlr = Mockito.mock(HlrService.class);
  private final Account sender = new Account(UUID.randomUUID(), FROM, 123_456);
  private final TransferService service =
      new TransferService(
          accounts,
          transactions,
          pdp,
          new RuleSet("rs-test", Map.of("CAP_FAILCLOSED", Map.of("xaf", 50000))),
          Clock.fixed(T0, ZoneOffset.UTC),
          new ObjectMapper(),
          hlr,
          Mockito.mock(NotificationService.class),
          Mockito.mock(LayerMode.class));

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(FROM)).thenReturn(Optional.of(sender));
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(new PendingTransaction(UUID.randomUUID(), "did-1"));
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-1"));
    when(hlr.currentImsi(FROM)).thenReturn(Optional.of("624019999999000"));
  }

  private JsonNode sent() throws Exception {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(pdp).evaluate(body.capture());
    return new ObjectMapper().readTree(body.getValue());
  }

  @Test
  void theRequest_carriesTheBalance_andTheDateOfTheLastSimSwap_neverItsReason() throws Exception {
    Instant first = T0.minusSeconds(86_400 * 30);
    Instant last = T0.minusSeconds(3_600);
    when(hlr.history(FROM))
        .thenReturn(
            List.of(
                new HlrReassignment(UUID.randomUUID(), FROM, "a", "b", "ag-1", "perte", first),
                new HlrReassignment(UUID.randomUUID(), FROM, "b", "c", "ag-2", "vol", last)));
    service.transferP2P(FROM, "677000002", 1_000, "USSD");
    JsonNode req = sent();
    assertEquals(123_456, req.get("balanceXaf").asLong());
    assertEquals(last.toString(), req.get("imsiChangedAt").asText());
    assertFalse(req.toString().contains("vol"), "le motif ne franchit jamais la frontière");
  }

  @Test
  void aNumberNeverReassigned_sendsNoImsiChangeDate() throws Exception {
    when(hlr.history(FROM)).thenReturn(List.of());
    service.transferP2P(FROM, "677000002", 1_000, "USSD");
    JsonNode req = sent();
    assertFalse(req.has("imsiChangedAt"));
    assertEquals(123_456, req.get("balanceXaf").asLong());
  }
}
