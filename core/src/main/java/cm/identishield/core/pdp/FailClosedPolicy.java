package cm.identishield.core.pdp;

/**
 * Politique fail-closed du coeur quand le PDP est indisponible (ES-11, ratifie le 2026-10-15) :
 * toute sortie sensible strictement au-dessus de CAP_FAILCLOSED est REFUSEE ; en dessous ou egale,
 * elle est autorisee en mode degrade (et journalisee par l'appelant). Le seuil vient de la
 * configuration versionnee, jamais d'un litteral.
 */
public final class FailClosedPolicy {

  private final long capFailClosedXaf;

  public FailClosedPolicy(long capFailClosedXaf) {
    this.capFailClosedXaf = capFailClosedXaf;
  }

  public Outcome onPdpUnavailable(long amountXaf) {
    return amountXaf > capFailClosedXaf ? Outcome.REFUSED_FAIL_CLOSED : Outcome.ALLOWED_DEGRADED;
  }
}
