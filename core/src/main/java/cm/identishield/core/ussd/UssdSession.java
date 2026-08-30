package cm.identishield.core.ussd;

import java.util.UUID;

/**
 * État d'une session USSD (EF-06). Machine à états minimale, UNE branche (transfert P2P) au mois 2
 * (ISC-SKEL §3) : AMOUNT -> BENEFICIARY -> CONFIRM. Aucune capacité cryptographique côté client
 * (USSD) : la preuve est NONE.
 */
public record UssdSession(UUID id, String msisdn, Step step, Long amountXaf, String beneficiary) {

  public enum Step {
    AMOUNT,
    BENEFICIARY,
    CONFIRM
  }
}
