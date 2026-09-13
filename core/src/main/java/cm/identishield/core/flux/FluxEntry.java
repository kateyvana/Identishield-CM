package cm.identishield.core.flux;

/**
 * Une ligne du flux pour la console (EF-30) : la transaction, sa décision et son decisionId. Au
 * mois 2 la seule décision est ALLOW (score 0, aucune règle) ; l'état EXECUTED la reflète.
 */
public record FluxEntry(
    String decisionId,
    String msisdn,
    String type,
    long amountXaf,
    String beneficiary,
    String decision,
    int score,
    String state,
    String createdAt) {}
