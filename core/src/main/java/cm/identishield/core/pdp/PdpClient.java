package cm.identishield.core.pdp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client du coeur vers le PDP (ES-11, EI-01). Proprietes gravees ici, mesurees en SPIKE-4 et fixees
 * par ADR-021 :
 *
 * <ul>
 *   <li><b>Connexions persistantes</b> : un unique {@link HttpClient} reutilise, qui met en cache
 *       et reutilise les connexions (OB-6 ; surcout 0,34 % vs 13,2 % en connexion-neuve).
 *   <li><b>Chaine ET nom</b> : le {@link SSLContext} verifie la chaine du serveur (ancre CA), et
 *       {@code endpointIdentificationAlgorithm=HTTPS} verifie le nom (SAN) — les deux (OB-6).
 *   <li><b>Delai</b> de 800 ms a la connexion et par requete.
 *   <li><b>Disjoncteur</b> devant les appels, et <b>fail-closed</b> au-dessus de CAP_PROB quand le
 *       PDP est indisponible (ES-11).
 * </ul>
 */
public final class PdpClient {

  // @implements ES-11
  // @implements EI-01

  private static final Logger log = LoggerFactory.getLogger(PdpClient.class);

  private final HttpClient http;
  private final PdpClientProperties props;
  private final CircuitBreaker breaker;
  private final FailClosedPolicy policy;

  public PdpClient(PdpClientProperties props, long capProbXaf, SSLContext ssl, Clock clock) {
    this.props = props;
    SSLParameters params = new SSLParameters();
    params.setEndpointIdentificationAlgorithm("HTTPS"); // verifie le NOM du serveur
    this.http =
        HttpClient.newBuilder()
            .sslContext(ssl)
            .sslParameters(params)
            .version(HttpClient.Version.HTTP_1_1) // keep-alive : connexions persistantes
            .connectTimeout(Duration.ofMillis(props.timeoutMs()))
            .build();
    this.breaker =
        new CircuitBreaker(props.breaker().failureThreshold(), props.breaker().cooldownMs(), clock);
    this.policy = new FailClosedPolicy(capProbXaf);
  }

  /** Vrai si le PDP repond 200 sur son point de sante, disjoncteur respecte. */
  public boolean pdpReachable() {
    if (!breaker.allowRequest()) {
      return false;
    }
    try {
      boolean ok = get(URI.create(props.baseUrl() + props.healthPath())) == 200;
      if (ok) {
        breaker.recordSuccess();
      } else {
        breaker.recordFailure();
      }
      return ok;
    } catch (Exception e) {
      breaker.recordFailure();
      return false;
    }
  }

  /**
   * Applique la politique quand une sortie sensible doit etre decidee. En T2-04, /evaluate n'existe
   * pas encore : si le PDP est joignable, l'issue est PDP_AVAILABLE (l'appel /evaluate viendra en
   * T2-05) ; sinon, fail-closed.
   */
  public Outcome gate(long amountXaf) {
    if (pdpReachable()) {
      return Outcome.PDP_AVAILABLE;
    }
    Outcome outcome = policy.onPdpUnavailable(amountXaf);
    if (outcome == Outcome.REFUSED_FAIL_CLOSED) {
      log.warn("PDP indisponible : sortie de {} XAF REFUSEE (> CAP_PROB), fail-closed", amountXaf);
    } else {
      log.warn(
          "PDP indisponible : sortie de {} XAF autorisee en mode DEGRADE (<= CAP_PROB)", amountXaf);
    }
    return outcome;
  }

  /**
   * Prechauffe la pile TLS et etablit la connexion persistante au demarrage, pour que la PREMIERE
   * decision reelle soit deja chaude (sinon le cout unique de chargement des classes TLS
   * depasserait le budget de 800 ms et declencherait un fail-closed parasite). Best-effort : les
   * erreurs sont ignorees et n'affectent pas le disjoncteur.
   */
  public void warmup() {
    try {
      get(URI.create(props.baseUrl() + props.healthPath()));
    } catch (Exception ignored) {
      // demarrage a froid : sans importance, la connexion sera retentee a l'usage
    }
  }

  /** Sonde brute d'un PDP cible : renvoie le motif de refus (4a/4b, OB-6). */
  public ProbeResult probe(URI target) {
    try {
      return ProbeResult.accepted(get(target));
    } catch (Exception e) {
      String msg = deepestMessage(e);
      return ProbeResult.rejected(classify(msg), msg);
    }
  }

  /** GET brut renvoyant le code HTTP sur la connexion persistante (bench, sonde). */
  public int getStatus(URI uri) throws Exception {
    return get(uri);
  }

  private int get(URI uri) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(props.timeoutMs())).GET().build();
    return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static String deepestMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    String m = c.getMessage();
    return m == null ? c.getClass().getSimpleName() : m;
  }

  private static ProbeResult.Reason classify(String msg) {
    String m = msg.toLowerCase();
    if (m.contains("no subject alternative")
        || m.contains("no name matching")
        || m.contains("doesn't match")
        || m.contains("does not match")
        || m.contains("hostname")) {
      return ProbeResult.Reason.NAME_MISMATCH;
    }
    if (m.contains("unable to find valid certification path")
        || m.contains("pkix")
        || m.contains("self-signed")
        || m.contains("self signed")) {
      return ProbeResult.Reason.CHAIN_OR_SIGNATURE;
    }
    return ProbeResult.Reason.OTHER;
  }
}
