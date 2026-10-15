package cm.identishield.pdp.rules;

import java.time.Instant;
import java.util.List;

/**
 * Une opération du compte telle que le PDP la voit : contrepartie (bénéficiaire d'une sortie,
 * émetteur d'une entrée), montant entier (ED-05), sens, instant serveur UTC (ED-04). Un historique
 * est la liste des opérations EXÉCUTÉES du compte, antérieures à l'opération évaluée.
 */
public record Operation(String counterpartyId, long amountXaf, boolean outgoing, Instant at) {

  /** ES-05b : contrepartie jamais vue sur le compte, dans un sens comme dans l'autre. */
  public static boolean isNewCounterparty(String counterpartyId, List<Operation> history) {
    return history.stream().noneMatch(o -> o.counterpartyId().equals(counterpartyId));
  }
}
