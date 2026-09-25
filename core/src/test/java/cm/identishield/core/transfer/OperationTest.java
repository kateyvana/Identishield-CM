package cm.identishield.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Types d'opération restants (EF-04) : cash-in, cash-out, marchand, facture, crédit. Un cas par
 * type vérifie que le cœur appelle /evaluate AVANT tout mouvement de solde et applique la décision.
 * Assertion négative : aucune sortie sensible n'est commise sans une décision du /evaluate.
 *
 * @covers EF-04
 * @covers EF-05
 */
class OperationTest {

  private static final String MSISDN = "677000001";
  private static final String COUNTERPARTY = "agent-000001";
  private static final long CAP_PROB = 25_000;

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final TransactionRepository transactions = mock(TransactionRepository.class);
  private final PdpClient pdp = mock(PdpClient.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-test", Map.of("CAP_PROB", Map.of("dailyXaf", 25000)));
  private final HlrService hlr = mock(HlrService.class);
  private final NotificationService notifications = mock(NotificationService.class);
  private final LayerMode layerMode = mock(LayerMode.class);

  private final TransferService service =
      new TransferService(
          accounts,
          transactions,
          pdp,
          ruleSet,
          Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC),
          new ObjectMapper(),
          hlr,
          notifications,
          layerMode);

  private final Account initiator = new Account(UUID.randomUUID(), MSISDN, 1_000_000);
  private final PendingTransaction pending = new PendingTransaction(UUID.randomUUID(), "did-op");

  @BeforeEach
  void wire() {
    when(accounts.findByMsisdn(MSISDN)).thenReturn(Optional.of(initiator));
    when(accounts.debit(any(), anyLong())).thenReturn(true);
    when(transactions.createPending(any(), anyString(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(pending);
  }

  /** Les quatre sorties : /evaluate AVANT le débit, débit à ALLOW, type et contrepartie émis. */
  @ParameterizedTest
  @EnumSource(
      value = OperationType.class,
      names = {"CASH_OUT", "MERCHANT", "BILL", "AIRTIME"})
  void outflowEvaluatesBeforeDebitingAndExecutesOnAllow(OperationType type) {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-op"));

    TransferResult r = service.operate(MSISDN, type, 10_000, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.EXECUTED, r.state());
    ArgumentCaptor<String> req = ArgumentCaptor.forClass(String.class);
    InOrder ord = inOrder(pdp, accounts);
    ord.verify(pdp).evaluate(req.capture()); // /evaluate d'abord
    ord.verify(accounts).debit(eq(initiator.id()), eq(10_000L)); // débit ensuite
    assertTrue(req.getValue().contains("\"type\":\"" + type.name() + "\""));
    assertTrue(req.getValue().contains("\"beneficiaryId\":\"" + COUNTERPARTY + "\""));
    verify(transactions).markExecuted(pending.id());
  }

  /** Cash-in : /evaluate AVANT le crédit, crédit à ALLOW, aucun débit (c'est une entrée). */
  @Test
  void cashInEvaluatesBeforeCreditingAndExecutesOnAllow() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-op"));

    TransferResult r = service.operate(MSISDN, OperationType.CASH_IN, 10_000, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.EXECUTED, r.state());
    InOrder ord = inOrder(pdp, accounts);
    ord.verify(pdp).evaluate(anyString()); // /evaluate d'abord
    ord.verify(accounts).credit(eq(initiator.id()), eq(10_000L)); // crédit ensuite
    verify(accounts, never()).debit(any(), anyLong()); // une entrée ne débite jamais l'initiateur
    verify(transactions).markExecuted(pending.id());
  }

  /**
   * Correction ES-11 (décision du propriétaire, 15/09) : une ENTRÉE n'est jamais refusée pour
   * fail-closed. PDP indisponible, CASH_IN de 100 000 FCFA (bien au-dessus de CAP_PROB) : le compte
   * est crédité, état EXECUTED_DEGRADED. Assertion négative : aucun CASH_IN n'est jamais rejeté.
   */
  @Test
  void cashInNeverFailsClosedAndRunsDegradedWhenPdpUnavailable() {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.unavailable());

    TransferResult r = service.operate(MSISDN, OperationType.CASH_IN, 100_000, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.EXECUTED_DEGRADED, r.state());
    verify(accounts).credit(eq(initiator.id()), eq(100_000L)); // crédité malgré PDP indisponible
    verify(transactions, never()).markRejected(any()); // jamais refusé pour fail-closed
    verify(transactions).markExecuted(pending.id());
  }

  /**
   * Assertion négative (EF-04) : PDP indisponible au-dessus de CAP_PROB, aucune sortie sensible
   * n'est commise (fail-closed), pour chacune des quatre sorties.
   */
  @ParameterizedTest
  @EnumSource(
      value = OperationType.class,
      names = {"CASH_OUT", "MERCHANT", "BILL", "AIRTIME"})
  void outflowCommitsNoDebitWhenPdpUnavailableAboveCap(OperationType type) {
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.unavailable());

    TransferResult r = service.operate(MSISDN, type, CAP_PROB + 1, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.REJECTED, r.state());
    verify(pdp).evaluate(anyString()); // /evaluate a bien été tenté
    verify(accounts, never()).debit(any(), anyLong()); // aucune sortie sensible commise
    verify(transactions).markRejected(pending.id());
  }

  /**
   * Mode sans couche (EF-07) : la couche de sécurité est désactivée — l'opération s'exécute SANS
   * appel /evaluate (l'état « avant » de la démonstration). L'opération passe quand même.
   */
  @Test
  void layerlessModeExecutesWithoutCallingEvaluate() {
    when(layerMode.isLayerless()).thenReturn(true);

    TransferResult r = service.operate(MSISDN, OperationType.CASH_OUT, 10_000, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.EXECUTED, r.state());
    verify(pdp, never()).evaluate(anyString()); // couche désactivée : /evaluate jamais appelé
    verify(accounts).debit(eq(initiator.id()), eq(10_000L)); // l'opération passe quand même
    verify(transactions).markExecuted(pending.id());
  }

  /**
   * Mode INACTIF (couche active, le défaut) : le chemin de contournement est INATTEIGNABLE. {@code
   * pdp.evaluate} est appelé EXACTEMENT une fois — donc le raccourci sans couche (qui retourne
   * AVANT d'appeler /evaluate) n'a PAS été emprunté. Preuve inverse de {@link
   * #layerlessModeExecutesWithoutCallingEvaluate} : vérifier que la porte se ferme, pas seulement
   * qu'elle s'ouvre.
   */
  @Test
  void layeredModeAlwaysGoesThroughEvaluateExactlyOnce() {
    when(layerMode.isLayerless()).thenReturn(false); // couche active, rendu explicite
    when(pdp.evaluate(anyString())).thenReturn(EvaluateOutcome.of("ALLOW", "did-op"));

    TransferResult r = service.operate(MSISDN, OperationType.CASH_OUT, 10_000, COUNTERPARTY, "APP");

    assertEquals(TransferResult.State.EXECUTED, r.state());
    verify(layerMode).isLayerless(); // le test de mode a bien été consulté
    verify(pdp, times(1)).evaluate(anyString()); // exactement une fois => raccourci NON emprunté
  }
}
