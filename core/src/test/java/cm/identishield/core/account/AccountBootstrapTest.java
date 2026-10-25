package cm.identishield.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cm.identishield.core.hlr.HlrService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * SIM des comptes techniques (décision du superviseur technique du 2026-10-25) : depuis A5-00
 * (#57), un émetteur sans SIM au HLR est refusé avant {@code /evaluate}. Le bootstrap pose la SIM
 * de 677000001 (l'IMSI déjà épinglé par le PDP) et de 677000002 (dérivation 624-01), sans jamais en
 * remplacer une, et séparément de la création des comptes.
 *
 * <p>@covers ED-09
 */
class AccountBootstrapTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final HlrService hlr = mock(HlrService.class);
  private final AccountBootstrap bootstrap =
      new AccountBootstrap(
          accounts, hlr, Clock.fixed(Instant.parse("2026-10-25T15:00:00Z"), ZoneOffset.UTC));

  @Test
  void theSenderGetsThePdpPinnedImsi_andTheBeneficiaryTheDerivedOne() {
    bootstrap.ensureSeedSubscribers();
    verify(hlr).ensureSubscriber("677000001", "624010000000000");
    verify(hlr).ensureSubscriber("677000002", "624010677000002");
  }

  @Test
  void theImsisAreFifteenDigits_inTheSimulatedOperatorRange() {
    for (String imsi :
        new String[] {AccountBootstrap.SENDER_IMSI, AccountBootstrap.BENEFICIARY_IMSI}) {
      assertEquals(15, imsi.length(), imsi);
      assertEquals("62401", imsi.substring(0, 5), "MCC 624, MNC 01 : " + imsi);
    }
  }

  @Test
  void seedingTheSims_goesOnlyThroughEnsureSubscriber_neverAReassignment() {
    bootstrap.ensureSeedSubscribers();
    bootstrap.ensureSeedSubscribers();
    // Idempotent par construction (INSERT … ON CONFLICT DO NOTHING) : jamais de réattribution,
    // donc jamais d'événement de changement d'IMSI ni de date de changement envoyée au PDP.
    verify(hlr, times(2)).ensureSubscriber("677000001", "624010000000000");
    verify(hlr, never()).reassign(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void creatingTheAccounts_doesNotTouchTheHlr_theVerificationModesSetTheirOwnInitialImsi() {
    bootstrap.ensureSeedAccounts();
    verifyNoInteractions(hlr);
  }
}
