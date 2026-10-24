package cm.identishield.core.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.hlr.ImsiChangeEvent;
import cm.identishield.core.mobile.MobileLedger.AccountView;
import cm.identishield.core.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Flux F côté cœur (carte B5) : quels événements lèvent quelle alerte, avec ou sans réponse ; le
 * seuil ALERT_REENROL compté par compte dans sa fenêtre, une seule alerte jusqu'au succès ; lever
 * une alerte n'échoue jamais l'opération qui la provoque.
 *
 * <p>@covers ES-27
 *
 * <p>@covers EF-47
 *
 * <p>@covers ES-28
 */
class SecurityAlertsTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000123");

  private final Alerts alerts = mock(Alerts.class);
  private final MobileLedger ledger = mock(MobileLedger.class);
  private final VirtualClock clock = new VirtualClock(T0);
  private final SecurityAlerts security =
      new SecurityAlerts(alerts, ledger, clock, 3, Duration.ofHours(1));

  @Test
  void simChange_onAKnownNumber_raisesAnActionableAlert_unknownNumberRaisesNothing() {
    when(ledger.findByMsisdn("677000123"))
        .thenReturn(Optional.of(new AccountView(ACCOUNT, "677000123", null, 0)));
    security.onImsiChanged(
        new ImsiChangeEvent("677000123", "624010000000001", "624010000000002", T0, "ag", "lost"));
    verify(alerts).raise(ACCOUNT, "SIM_CHANGED", SecurityAlerts.TEXT_SIM_CHANGED, true, T0);
    when(ledger.findByMsisdn("677000124")).thenReturn(Optional.empty());
    security.onImsiChanged(
        new ImsiChangeEvent("677000124", null, "624010000000003", T0, "ag", "new"));
    verify(alerts, times(1)).raise(any(), anyString(), anyString(), anyBoolean(), any());
  }

  @Test
  void reenrolFailures_alertOnceAtTheThreshold_withinTheWindow_andResetOnSuccess() {
    security.reenrolFailed(ACCOUNT);
    security.reenrolFailed(ACCOUNT);
    verify(alerts, never()).raise(any(), anyString(), anyString(), anyBoolean(), any());
    security.reenrolFailed(ACCOUNT); // 3e échec : seuil atteint
    verify(alerts).raise(ACCOUNT, "REENROL_ATTEMPT", SecurityAlerts.TEXT_REENROL_ATTEMPT, true, T0);
    security.reenrolFailed(ACCOUNT);
    security.reenrolFailed(ACCOUNT);
    verify(alerts, times(1)).raise(any(), anyString(), anyString(), anyBoolean(), any());
    // Succès : compteur et alerte repartent ; trois nouveaux échecs alertent de nouveau.
    security.reenrolSucceeded(ACCOUNT, false);
    verify(alerts, never())
        .raise(eq(ACCOUNT), eq("DEVICE_REBOUND"), anyString(), anyBoolean(), any());
    clock.advance(Duration.ofMinutes(1));
    security.reenrolFailed(ACCOUNT);
    security.reenrolFailed(ACCOUNT);
    security.reenrolFailed(ACCOUNT);
    verify(alerts, times(2))
        .raise(eq(ACCOUNT), eq("REENROL_ATTEMPT"), anyString(), eq(true), any());
  }

  @Test
  void reenrolFailures_outsideTheWindow_doNotCount() {
    security.reenrolFailed(ACCOUNT);
    security.reenrolFailed(ACCOUNT);
    clock.advance(Duration.ofHours(1).plusSeconds(1));
    security.reenrolFailed(ACCOUNT); // les deux premiers sont sortis de la fenêtre
    verify(alerts, never()).raise(any(), anyString(), anyString(), anyBoolean(), any());
  }

  @Test
  void rebound_isInformative_deferredAndFrozenToo_textsCarryNoLinkCodeOrPrompt() {
    security.reenrolSucceeded(ACCOUNT, true);
    verify(alerts).raise(ACCOUNT, "DEVICE_REBOUND", SecurityAlerts.TEXT_DEVICE_REBOUND, false, T0);
    security.transferDeferred(ACCOUNT, 5_000);
    verify(alerts)
        .raise(
            ACCOUNT,
            "TRANSFER_DEFERRED",
            "Votre envoi de 5000 FCFA sera exécuté plus tard.",
            false,
            T0);
    security.transferFrozen(ACCOUNT);
    verify(alerts)
        .raise(ACCOUNT, "TRANSFER_FROZEN", SecurityAlerts.TEXT_TRANSFER_FROZEN, false, T0);
    for (String text :
        new String[] {
          SecurityAlerts.TEXT_SIM_CHANGED,
          SecurityAlerts.TEXT_REENROL_ATTEMPT,
          SecurityAlerts.TEXT_DEVICE_REBOUND,
          SecurityAlerts.TEXT_TRANSFER_DEFERRED,
          SecurityAlerts.TEXT_TRANSFER_FROZEN
        }) {
      org.junit.jupiter.api.Assertions.assertFalse(
          text.matches("(?is).*(http|www\\.|code|saisir|tapez|entrez|\\d{4,}).*"), text);
    }
  }

  @Test
  void aFailingAlertStore_neverFailsTheCaller() {
    doThrow(new IllegalStateException("base indisponible"))
        .when(alerts)
        .raise(any(), anyString(), anyString(), anyBoolean(), any());
    when(ledger.findByMsisdn("677000123"))
        .thenReturn(Optional.of(new AccountView(ACCOUNT, "677000123", null, 0)));
    security.onImsiChanged(
        new ImsiChangeEvent("677000123", "624010000000001", "624010000000002", T0, "ag", "lost"));
    security.transferFrozen(ACCOUNT);
    security.reenrolSucceeded(ACCOUNT, true);
    // Aucune exception propagée : la réattribution HLR et le virement ne dépendent pas de l'alerte.
  }
}
