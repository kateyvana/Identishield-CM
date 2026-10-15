package cm.identishield.pdp.rules;

import java.util.List;

/**
 * Une règle = une classe = une contribution (ISC-SAD §5). Aucune règle ne connaît les autres ni ne
 * lit l'horloge : l'instant est celui de l'opération. Fonction pure de l'historique (OB-1).
 */
interface Rule {

  /** Contribution de cette règle à une sortie ; {@code null} si elle ne se déclenche pas. */
  Score.Contribution evaluate(Operation op, List<Operation> history);
}
