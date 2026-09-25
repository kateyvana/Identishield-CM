package cm.identishield.core.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.identishield.core.notification.NotificationRefusedException.Reason;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou de contenu ENF-17 (anti-hameçonnage, AC-01) : un contenu portant un lien, un numéro de
 * rappel ou une demande de secret est REFUSÉ — prouvé par un refus effectif, pas un signalement.
 *
 * @covers ENF-17
 * @covers AC-01
 */
class NotificationContentPolicyTest {

  @Test
  void acceptsCleanSecurityContent() {
    // Un montant court (six chiffres) ne déclenche pas le détecteur de numéro.
    assertDoesNotThrow(
        () ->
            NotificationContentPolicy.enforce(
                "TRANSFER_EXECUTED", "Opération de 100000 FCFA. Rendez-vous en agence si besoin."));
    // Le contenu exact émis par un transfert exécuté doit passer.
    assertDoesNotThrow(
        () ->
            NotificationContentPolicy.enforce(
                "P2P_EXECUTED",
                "Si vous n'êtes pas à l'origine de cette opération, rendez-vous en agence."));
  }

  @Test
  void refusesWebAddresses() {
    assertEquals(Reason.WEB_LINK, reasonOf("ALERTE", "Vérifiez sur https://votre-banque.cm"));
    assertEquals(Reason.WEB_LINK, reasonOf("ALERTE", "Allez sur www.phish.cm maintenant"));
    assertEquals(Reason.WEB_LINK, reasonOf("ALERTE", "Connectez-vous à secure-login.com"));
  }

  @Test
  void refusesCallbackPhoneNumbers() {
    assertEquals(Reason.PHONE_NUMBER, reasonOf("ALERTE", "Rappelez le 677 12 34 56"));
    assertEquals(Reason.PHONE_NUMBER, reasonOf("ALERTE", "Contactez +237677123456"));
  }

  @Test
  void refusesSecretRequests() {
    assertEquals(Reason.SECRET_REQUEST, reasonOf("ALERTE", "Communiquez votre code à l'agent"));
    assertEquals(Reason.SECRET_REQUEST, reasonOf("ALERTE", "Donnez votre OTP reçu par SMS"));
    assertEquals(Reason.SECRET_REQUEST, reasonOf("ALERTE", "Saisissez votre PIN pour confirmer"));
  }

  private static Reason reasonOf(String type, String action) {
    return assertThrows(
            NotificationRefusedException.class,
            () -> NotificationContentPolicy.enforce(type, action))
        .reason();
  }
}
