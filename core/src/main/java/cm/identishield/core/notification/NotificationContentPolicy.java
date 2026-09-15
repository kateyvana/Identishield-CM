package cm.identishield.core.notification;

import cm.identishield.core.notification.NotificationRefusedException.Reason;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Garde-fou de contenu ENF-17 (anti-hameçonnage). AC-01 (victime consentante) est la seule attaque
 * sans contre-mesure technique : si le système émet des messages avec des liens ou des demandes de
 * code, il éduque les utilisateurs à cliquer et donne à un fraudeur un gabarit de hameçonnage. La
 * règle est donc appliquée PAR LE CODE : un contenu qui la viole est REFUSÉ à l'émission ({@link
 * NotificationRefusedException}), jamais seulement signalé.
 *
 * <p>Cette classe ne valide QUE les champs libres fournis par l'appelant (type d'événement,
 * action). L'horodatage serveur est ajouté séparément par {@link NotificationService} et n'est
 * jamais passé ici — sinon la date ISO (huit chiffres séparés par des tirets) déclencherait à tort
 * le détecteur de numéro. Le volet POSITIF d'ENF-17 (type + horodatage + action présents) est
 * garanti par construction dans le service, pas ici.
 *
 * <p><b>Revue ligne à ligne requise</b> : contrainte de sécurité (vecteur de hameçonnage, ENF-08c).
 */
public final class NotificationContentPolicy {

  // Lien cliquable / adresse web : schéma http(s), "www.", ou un domaine "nom.tld" dont le TLD est
  // reconnu (la liste évite les faux positifs sur la ponctuation de phrase, ex. « ...agence.Merci
  // »).
  private static final Pattern WEB_LINK =
      Pattern.compile(
          "(?i)(?:https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|net|org|info|io|co|cm|fr|biz|xyz|app|link|click|ru|cn|tk)\\b)");

  // Numéro de rappel : au moins huit chiffres, séparateurs espace/point/tiret tolérés, « + »
  // optionnel.
  // Un montant usuel (≤ six chiffres) ne matche pas ; l'horodatage n'est pas soumis à ce détecteur.
  private static final Pattern PHONE_NUMBER = Pattern.compile("\\+?(?:\\d[ .\\-]?){8,}");

  // Demande de code / secret : une notification de sécurité ne demande JAMAIS de secret (ENF-17).
  // Le seul vocabulaire de secret dans un message sortant est déjà une violation.
  private static final Pattern SECRET_REQUEST =
      Pattern.compile("(?i)\\b(?:code|otp|pin|secret|mdp|mot\\s+de\\s+passe)\\b");

  private NotificationContentPolicy() {}

  /**
   * Refuse le contenu s'il porte un vecteur de hameçonnage. Ordre : lien, puis numéro, puis demande
   * de secret. Lève {@link NotificationRefusedException} avec le motif exact ; ne renvoie rien si
   * le contenu est acceptable.
   */
  public static void enforce(String... freeTextFields) {
    String content = String.join("\n", freeTextFields).toLowerCase(Locale.ROOT);
    if (WEB_LINK.matcher(content).find()) {
      throw new NotificationRefusedException(
          Reason.WEB_LINK, "notification refusée (ENF-17) : lien cliquable / adresse web interdit");
    }
    if (PHONE_NUMBER.matcher(content).find()) {
      throw new NotificationRefusedException(
          Reason.PHONE_NUMBER, "notification refusée (ENF-17) : numéro de rappel interdit");
    }
    if (SECRET_REQUEST.matcher(content).find()) {
      throw new NotificationRefusedException(
          Reason.SECRET_REQUEST,
          "notification refusée (ENF-17) : demande de code / secret interdite");
    }
  }
}
