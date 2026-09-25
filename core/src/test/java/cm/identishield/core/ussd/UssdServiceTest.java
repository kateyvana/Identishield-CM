package cm.identishield.core.ussd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.transfer.TransferResult;
import cm.identishield.core.transfer.TransferService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * @covers EF-06 (machine a etats de session USSD, une branche menant au transfert).
 */
class UssdServiceTest {

  private final UssdRepository sessions = mock(UssdRepository.class);
  private final TransferService transfers = mock(TransferService.class);
  private final UssdService ussd =
      new UssdService(
          sessions, transfers, Clock.fixed(Instant.parse("2026-08-26T09:15:00Z"), ZoneOffset.UTC));

  private static final String FROM = "677000001";
  private static final String BENEF = "677000002";
  private final UUID sid = UUID.randomUUID();

  @Test
  void walksThroughStatesToAnExecutedTransfer() {
    when(sessions.create(eq(FROM), eq(UssdSession.Step.AMOUNT), any())).thenReturn(sid);
    UssdService.Interaction dial = ussd.dial(FROM);
    assertEquals(sid, dial.sessionId());
    assertTrue(dial.response().sessionOpen());

    // Étape MONTANT -> BÉNÉFICIAIRE.
    when(sessions.findActive(eq(sid), any()))
        .thenReturn(Optional.of(new UssdSession(sid, FROM, UssdSession.Step.AMOUNT, null, null)));
    UssdResponse r1 = ussd.input(sid, "15000");
    assertTrue(r1.sessionOpen());
    verify(sessions)
        .update(
            ArgumentMatchers.argThat(
                s -> s.step() == UssdSession.Step.BENEFICIARY && s.amountXaf() == 15000L));

    // Étape CONFIRMATION -> transfert exécuté.
    when(sessions.findActive(eq(sid), any()))
        .thenReturn(
            Optional.of(new UssdSession(sid, FROM, UssdSession.Step.CONFIRM, 15000L, BENEF)));
    when(transfers.transferP2P(FROM, BENEF, 15000L, "USSD"))
        .thenReturn(TransferResult.executed("did-1"));
    UssdResponse fin = ussd.input(sid, "1");

    assertFalse(fin.sessionOpen());
    assertTrue(fin.message().contains("EXECUTED"));
    verify(transfers).transferP2P(FROM, BENEF, 15000L, "USSD");
    verify(sessions).delete(sid);
  }

  @Test
  void expiredOrUnknownSessionEndsWithoutTransfer() {
    when(sessions.findActive(eq(sid), any())).thenReturn(Optional.empty());
    UssdResponse r = ussd.input(sid, "anything");
    assertFalse(r.sessionOpen());
    verify(transfers, never()).transferP2P(any(), any(), anyLong(), any());
  }
}
