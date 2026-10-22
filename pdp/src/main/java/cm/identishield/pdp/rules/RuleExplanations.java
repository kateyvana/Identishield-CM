package cm.identishield.pdp.rules;

import java.util.Map;

/**
 * Explication factuelle et générique de chaque règle déclenchée, portée par la décision et son
 * entrée d'audit (contrat : {@code explanation} obligatoire). AUCUNE donnée du client : ni numéro,
 * ni montant, ni nom. La carte A5 l'enrichira.
 */
public final class RuleExplanations {

  private static final Map<String, String> TEXTS =
      Map.of(
          "NEW_BENEFICIARY", "bénéficiaire jamais vu dans l'historique du compte",
          "NEW_BENEFICIARY_HIGH",
              "bénéficiaire jamais vu dans l'historique du compte, montant au moins égal au seuil"
                  + " THR_HIGH",
          "VELOCITY",
              "nombre de sorties ou de nouveaux bénéficiaires au-delà du seuil VEL sur sa fenêtre",
          "NIGHT", "sortie dans la fenêtre NIGHT, en heure locale de NIGHT_ZONE");

  private RuleExplanations() {}

  /** Explication d'une règle ; une règle sans explication est une erreur, jamais un texte vide. */
  public static String of(String ruleId) {
    String text = TEXTS.get(ruleId);
    if (text == null) {
      throw new IllegalStateException("règle sans explication : " + ruleId);
    }
    return text;
  }
}
