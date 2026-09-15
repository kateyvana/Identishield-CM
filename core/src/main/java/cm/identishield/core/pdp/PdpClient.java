package cm.identishield.core.pdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
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
  private final ObjectMapper json;

  public PdpClient(
      PdpClientProperties props, long capProbXaf, SSLContext ssl, Clock clock, ObjectMapper json) {
    this.props = props;
    this.json = json;
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

  /**
   * État courant du disjoncteur, pour l'affichage console (EF-36) : reflète l'historique des appels
   * réels, sans appel réseau supplémentaire. CLOSED = disponible, HALF_OPEN = dégradé, OPEN =
   * indisponible.
   */
  public CircuitBreaker.State circuitState() {
    return breaker.state();
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
   * Appelle POST /evaluate (mTLS, connexion persistante) et renvoie la decision. Sur
   * indisponibilite (disjoncteur ouvert, code != 200, erreur), renvoie une issue indisponible : le
   * coeur applique alors sa politique fail-closed (ES-11).
   */
  public EvaluateOutcome evaluate(String requestJson) {
    if (!breaker.allowRequest()) {
      return EvaluateOutcome.unavailable();
    }
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(props.baseUrl() + "/evaluate"))
              .timeout(Duration.ofMillis(props.timeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestJson))
              .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        breaker.recordSuccess();
        Map<String, Object> body = parseBody(resp.body());
        return EvaluateOutcome.of((String) body.get("decision"), (String) body.get("decisionId"));
      }
      breaker.recordFailure();
      return EvaluateOutcome.unavailable();
    } catch (Exception e) {
      breaker.recordFailure();
      return EvaluateOutcome.unavailable();
    }
  }

  private Map<String, Object> parseBody(String body) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = json.readValue(body, Map.class);
      return m;
    } catch (Exception e) {
      throw new IllegalStateException("reponse /evaluate illisible", e);
    }
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
