package cm.identishield.core.pdp;

/** Issue d'une demande de decision cote coeur (ES-11). */
public enum Outcome {
  /** PDP joignable : la decision reelle viendra de /evaluate (T2-05). */
  PDP_AVAILABLE,
  /** PDP indisponible et montant <= CAP_PROB : sortie autorisee en mode degrade, journalisee. */
  ALLOWED_DEGRADED,
  /** PDP indisponible et montant > CAP_PROB : sortie REFUSEE (fail-closed). */
  REFUSED_FAIL_CLOSED
}
