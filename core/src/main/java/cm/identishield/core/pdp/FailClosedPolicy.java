package cm.identishield.core.pdp;

/**
 * Politique fail-closed du coeur quand le PDP est indisponible (ES-11) : toute sortie sensible
 * strictement au-dessus de CAP_PROB est REFUSEE ; en dessous ou egale, elle est autorisee en mode
 * degrade (et journalisee par l'appelant). Le seuil CAP_PROB vient de la configuration versionnee,
 * jamais d'un litteral.
 */
public final class FailClosedPolicy {

  private final long capProbXaf;

  public FailClosedPolicy(long capProbXaf) {
    this.capProbXaf = capProbXaf;
  }

  public Outcome onPdpUnavailable(long amountXaf) {
    return amountXaf > capProbXaf ? Outcome.REFUSED_FAIL_CLOSED : Outcome.ALLOWED_DEGRADED;
  }
}
