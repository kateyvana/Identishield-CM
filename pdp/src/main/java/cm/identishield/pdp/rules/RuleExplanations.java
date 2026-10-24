package cm.identishield.pdp.rules;

import java.util.Map;

/**
 * Explication factuelle et générique portée par la décision, son entrée d'audit et les transitions
 * d'état de confiance (carte A5, #39). AUCUNE donnée du client : ni numéro, ni montant, ni nom.
 *
 * <p>Trois surfaces, trois dictionnaires :
 *
 * <ul>
 *   <li>{@link #of(String)} : règle du moteur, pour chaque élément de {@code triggeredRules}
 *       (contrat : {@code explanation} obligatoire) ;
 *   <li>{@link #ofVeto(String)} : raison lisible d'un veto, pour les décisions FREEZE ou REJECTED
 *       portées par un veto, et pour l'ALLOW sans règle déclenchée (clé {@code NONE_ALLOW}) ;
 *   <li>{@link #ofTrustEvent(String, String)} : libellé lisible d'un événement de transition d'état
 *       de confiance (TRUST_*).
 * </ul>
 *
 * <p>Une clé sans texte est une erreur, jamais un texte vide : une raison « manquante en silence »
 * est pire que pas de raison. La couverture est vérifiée par un test unitaire.
 */
public final class RuleExplanations {

  /** Raison écrite sur un ALLOW rendu sans aucune règle déclenchée. Pseudo-clé de veto. */
  public static final String NONE_ALLOW = "NONE_ALLOW";

  private static final Map<String, String> RULES =
      Map.of(
          "NEW_BENEFICIARY", "bénéficiaire jamais vu dans l'historique du compte",
          "NEW_BENEFICIARY_HIGH",
              "bénéficiaire jamais vu dans l'historique du compte, montant au moins égal au seuil"
                  + " THR_HIGH",
          "VELOCITY",
              "nombre de sorties ou de nouveaux bénéficiaires au-delà du seuil VEL sur sa fenêtre",
          "NIGHT", "sortie dans la fenêtre NIGHT, en heure locale de NIGHT_ZONE");

  private static final Map<String, String> VETOS =
      Map.of(
          "SIGNATURE_INVALID",
          "la signature de la requête n'a pas été reconnue par la clé d'appareil enregistrée",
          "PROBATION_VIOLATION",
          "première sortie en probation vers un bénéficiaire jamais vu, ou cumul de probation"
              + " dépassé : le compte est gelé",
          "ACCOUNT_FROZEN",
          "le compte est gelé : aucune sortie ne passe tant que le gel court",
          "INSUFFICIENT_FUNDS",
          "le montant demandé dépasse le solde connu : opération refusée avant d'entrer dans"
              + " l'historique autorisé",
          NONE_ALLOW,
          "aucune règle du moteur n'a atteint le seuil, et aucun veto ne s'applique");

  private static final Map<String, String> TRUST_EVENTS =
      Map.of(
          "TRUST_PROBATION_ENTERED",
              "entrée en probation à la suite d'un changement d'IMSI, pendant CAR_IMSI",
          "TRUST_PROBATION_EXPIRED", "fin de la fenêtre de probation sans incident, retour TRUSTED",
          "TRUST_FROZEN", "compte gelé à la suite d'un veto ou d'une décision",
          "TRUST_UNFROZEN", "levée du gel : contact de confiance, réenrôlement ou TTL écoulé");

  private RuleExplanations() {}

  /** Explication d'une règle du moteur. Une règle sans texte est une erreur. */
  public static String of(String ruleId) {
    String text = RULES.get(ruleId);
    if (text == null) {
      throw new IllegalStateException("règle sans explication : " + ruleId);
    }
    return text;
  }

  /**
   * Raison lisible d'un veto, ou de l'ALLOW sans règle déclenchée ({@link #NONE_ALLOW}). Un veto
   * sans texte est une erreur. Appeler avec {@code "NONE"} est interdit : le code appelant doit
   * passer {@link #NONE_ALLOW} si la décision est ALLOW sans règle.
   */
  public static String ofVeto(String vetoApplied) {
    String text = VETOS.get(vetoApplied);
    if (text == null) {
      throw new IllegalStateException("veto sans explication : " + vetoApplied);
    }
    return text;
  }

  /**
   * Libellé lisible d'une transition d'état de confiance (TRUST_*). Un événement sans texte est une
   * erreur. La cause (IMSI_CHANGE, CAR_IMSI_EXPIRED, FREEZE_DECISION, NOT_ME, TTL_FREEZE_EXPIRED,
   * RELEASE) est écrite à côté dans l'entrée d'audit ; elle est inchangée par cette méthode.
   */
  public static String ofTrustEvent(String event, String cause) {
    String text = TRUST_EVENTS.get(event);
    if (text == null) {
      throw new IllegalStateException("événement de confiance sans explication : " + event);
    }
    return text + " (" + cause + ")";
  }
}
