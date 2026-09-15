package cm.identishield.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.hlr.HlrService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sous-système de notification (EF-09) : un SMS vise l'IMSI courant, le corps porte type +
 * horodatage + action (ENF-17 positif), et un contenu de hameçonnage est refusé sans rien
 * persister.
 *
 * @covers EF-09
 * @covers ENF-17
 */
class NotificationServiceTest {

  private static final String MSISDN = "699000001";
  private static final String IMSI = "624010000000009";
  private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

  private final NotificationRepository repo = mock(NotificationRepository.class);
  private final HlrService hlr = mock(HlrService.class);
  private final NotificationService service =
      new NotificationService(repo, hlr, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void smsTargetsCurrentImsiAndComposesConstrainedBody() {
    when(hlr.currentImsi(MSISDN)).thenReturn(Optional.of(IMSI));
    when(repo.insert(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new Notification(
                UUID.randomUUID(), MSISDN, NotificationChannel.SMS, IMSI, "T", "B", NOW));

    service.emit(MSISDN, NotificationChannel.SMS, "TRANSFER_EXECUTED", "Rendez-vous en agence.");

    ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(repo)
        .insert(
            eq(MSISDN),
            eq(NotificationChannel.SMS),
            ref.capture(),
            eq("TRANSFER_EXECUTED"),
            body.capture(),
            eq(NOW));
    assertEquals(IMSI, ref.getValue()); // SMS vers l'IMSI courant (même faiblesse que l'OTP)
    assertTrue(body.getValue().contains("TRANSFER_EXECUTED"), "type d'événement");
    assertTrue(body.getValue().contains("2026-09-15T10:00:00Z"), "horodatage serveur (ED-04)");
    assertTrue(body.getValue().contains("Rendez-vous en agence."), "action possible");
  }

  @Test
  void refusesPhishingContentAndPersistsNothing() {
    assertThrows(
        NotificationRefusedException.class,
        () -> service.emit(MSISDN, NotificationChannel.SMS, "ALERTE", "Cliquez https://x.cm"));
    verify(repo, never()).insert(any(), any(), any(), any(), any(), any());
  }
}
