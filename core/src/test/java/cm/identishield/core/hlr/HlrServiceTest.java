package cm.identishield.core.hlr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Réattribution : écrit l'historique et bascule l'IMSI actif avec l'horodatage de l'horloge
 * INJECTÉE (jamais un DEFAULT de base), et publie l'événement portant l'ancien et le nouvel IMSI.
 * Le motif vit dans l'événement interne (traçabilité), pas au-delà.
 *
 * @covers EF-02
 * @covers EF-08
 * @covers EI-02
 */
class HlrServiceTest {

  private static final String MSISDN = "699000001";
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  private final HlrRepository repo = mock(HlrRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final HlrService service = new HlrService(repo, clock, events);

  @Test
  void reassignAppendsHistoryFlipsImsiAndPublishesEventFromInjectedClock() {
    when(repo.currentImsi(MSISDN)).thenReturn(Optional.of("624010000000001"));

    ImsiChangeEvent returned =
        service.reassign(MSISDN, "624010000000002", "agent-kyc-01", "motif-declare-par-agent");

    // Historique en écriture seule + horodatage de l'horloge injectée (OB-1, ED-04).
    verify(repo)
        .appendReassignment(
            MSISDN,
            "624010000000001",
            "624010000000002",
            "agent-kyc-01",
            "motif-declare-par-agent",
            NOW);
    verify(repo).setCurrentImsi(MSISDN, "624010000000002", NOW);

    // L'événement porte l'ancien et le nouvel IMSI et l'horodatage du changement (EF-08).
    ArgumentCaptor<ImsiChangeEvent> captor = ArgumentCaptor.forClass(ImsiChangeEvent.class);
    verify(events).publishEvent(captor.capture());
    ImsiChangeEvent published = captor.getValue();
    assertEquals(MSISDN, published.msisdn());
    assertEquals("624010000000001", published.oldImsi());
    assertEquals("624010000000002", published.newImsi());
    assertEquals(NOW, published.changedAt());
    assertEquals("motif-declare-par-agent", published.reason()); // motif = traçabilité interne
    assertEquals(published, returned);
  }
}
