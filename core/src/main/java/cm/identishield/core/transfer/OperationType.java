package cm.identishield.core.transfer;

/**
 * Les six types d'opération du cœur (EF-04). Les codes sont EXACTEMENT ceux de l'énumération {@code
 * type} du contrat /evaluate ({@code P2P, CASH_OUT, MERCHANT, BILL, AIRTIME, CASH_IN}) — {@link
 * #name()} sert donc de valeur émise, sans table de correspondance.
 *
 * <p>La <b>direction</b> décide l'effet sur le solde de l'initiateur : une {@code OUTFLOW} le
 * débite (sortie sensible, soumise au fail-closed ES-11), une {@code INFLOW} le crédite. Seul le
 * P2P règle en plus un bénéficiaire intra-système ({@code settlesCounterparty}) ; pour les autres,
 * la contrepartie (agent, marchand, facturier, opérateur) est hors du système simulé — son
 * identifiant remplit le champ {@code beneficiaryId} requis par le contrat, sans mouvement de
 * compte associé.
 */
public enum OperationType {
  P2P(Direction.OUTFLOW, true),
  CASH_OUT(Direction.OUTFLOW, false),
  MERCHANT(Direction.OUTFLOW, false),
  BILL(Direction.OUTFLOW, false),
  AIRTIME(Direction.OUTFLOW, false),
  CASH_IN(Direction.INFLOW, false);

  /** Sens du mouvement sur le compte de l'initiateur. */
  public enum Direction {
    OUTFLOW,
    INFLOW
  }

  private final Direction direction;
  private final boolean settlesCounterparty;

  OperationType(Direction direction, boolean settlesCounterparty) {
    this.direction = direction;
    this.settlesCounterparty = settlesCounterparty;
  }

  public Direction direction() {
    return direction;
  }

  /** Vrai si l'opération débite l'initiateur (sortie sensible : P2P et les quatre sorties). */
  public boolean isOutflow() {
    return direction == Direction.OUTFLOW;
  }

  /** Vrai si un compte bénéficiaire intra-système doit être crédité (P2P uniquement). */
  public boolean settlesCounterparty() {
    return settlesCounterparty;
  }
}
