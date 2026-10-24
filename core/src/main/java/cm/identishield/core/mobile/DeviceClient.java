package cm.identishield.core.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Client du cœur vers les routes internes /device/* du PDP (contracts/device.yaml), en mTLS comme
 * /evaluate : même {@link SSLContext}, vérification de la chaîne ET du nom. Il ne décide rien : il
 * rapporte le code HTTP et le corps, ou {@link Reply#UNREACHABLE} si la réponse n'est pas arrivée
 * (PDP injoignable, délai dépassé). C'est {@link MobileEnrolment} qui arbitre. Aucun corps n'est
 * journalisé : il porte le code de récupération.
 */
// @implements EI-01
public class DeviceClient {

  /** Réponse du PDP : code HTTP et corps JSON ; code {@link #UNREACHABLE} si rien n'est arrivé. */
  public record Reply(int status, Map<String, Object> body) {
    public static final int UNREACHABLE = 0;

    static Reply unreachable() {
      return new Reply(UNREACHABLE, Map.of());
    }

    String text(String field) {
      return body.get(field) instanceof String s ? s : null;
    }
  }

  private final HttpClient http;
  private final String baseUrl;
  private final Duration timeout;
  private final ObjectMapper json;

  public DeviceClient(String baseUrl, long timeoutMs, SSLContext ssl, ObjectMapper json) {
    this.baseUrl = baseUrl;
    this.timeout = Duration.ofMillis(timeoutMs);
    this.json = json;
    SSLParameters params = new SSLParameters();
    params.setEndpointIdentificationAlgorithm("HTTPS"); // vérifie le NOM du serveur
    this.http =
        HttpClient.newBuilder()
            .sslContext(ssl)
            .sslParameters(params)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build();
  }

  /** GET /device/state : le FAIT de liaison du compte (NONE, PENDING ou ACTIVE). */
  public Reply state(String accountId) {
    return send(
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl
                        + "/device/state?accountId="
                        + URLEncoder.encode(accountId, StandardCharsets.UTF_8)))
            .GET());
  }

  /** POST /device/register : clé EN ATTENTE et code de récupération émis une fois. */
  public Reply register(
      String accountId,
      String publicKey,
      List<String> attestationChain,
      String attestationChallenge,
      String challengeSignature) {
    return post(
        "/device/register",
        Map.of(
            "accountId", accountId,
            "publicKey", publicKey,
            "attestationChain", attestationChain,
            "attestationChallenge", attestationChallenge,
            "challengeSignature", challengeSignature));
  }

  /**
   * GET /device/{deviceId}/nonce : nonce du PDP émis pour le compte de cette clé ACTIVE (carte B4,
   * décision du 2026-10-24, point 4 : le défi de session EST le nonce du PDP). 404 si aucune clé
   * ACTIVE ne porte cet identifiant.
   */
  public Reply nonce(String deviceId) {
    return send(
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl
                        + "/device/"
                        + URLEncoder.encode(deviceId, StandardCharsets.UTF_8)
                        + "/nonce"))
            .GET());
  }

  /**
   * POST /device/verify : la signature du défi par la clé ACTIVE {@code deviceId} du compte, défi
   * consommé si elle est valide. Le PDP répond un verdict : VALID, INVALID ou REVOKED.
   */
  public Reply verify(String accountId, String deviceId, String challenge, String signature) {
    return verify(accountId, deviceId, challenge, signature, null);
  }

  /**
   * Variante (carte B6) : la signature porte sur {@code payload} (chaîne canonique ADR-018, par
   * exemple {@code accountId|CANCEL|transferId|nonce}) et c'est {@code nonce} qui est consommé.
   */
  public Reply verify(
      String accountId, String deviceId, String payload, String signature, String nonce) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("accountId", accountId);
    body.put("deviceId", deviceId);
    body.put("challenge", payload);
    body.put("signature", signature);
    if (nonce != null) {
      body.put("nonce", nonce);
    }
    return post("/device/verify", body);
  }

  /**
   * GET /trust/{accountId} (contracts/device.yaml, décision du superviseur du 2026-10-24) : l'état
   * de confiance du compte, lecture seule, sans écriture d'audit ; 404 ACCOUNT_UNKNOWN si le PDP n'a
   * jamais vu le compte. Le PDP énonce, le cœur recopie.
   */
  public Reply trust(String accountId) {
    return send(
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl + "/trust/" + URLEncoder.encode(accountId, StandardCharsets.UTF_8)))
            .GET());
  }

  /** GET /nonce?accountId= (contrats/evaluate.yaml, ES-10) : nonce pour une opération signée. */
  public Reply nonceForAccount(String accountId) {
    return send(
        HttpRequest.newBuilder(
                URI.create(
                    baseUrl
                        + "/nonce?accountId="
                        + URLEncoder.encode(accountId, StandardCharsets.UTF_8)))
            .GET());
  }

  /** POST /device/activate : la clé EN ATTENTE devient ACTIVE si le code est le bon. */
  public Reply activate(String accountId, String deviceId, String recoveryCode) {
    return post(
        "/device/activate",
        Map.of("accountId", accountId, "deviceId", deviceId, "recoveryCode", recoveryCode));
  }

  private Reply post(String path, Map<String, Object> body) {
    try {
      return send(
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("corps /device illisible", e);
    }
  }

  private Reply send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> resp =
          http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(resp.statusCode(), parse(resp.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Reply.unreachable();
    } catch (Exception e) {
      return Reply.unreachable();
    }
  }

  private Map<String, Object> parse(String body) {
    if (body == null || body.isBlank()) {
      return Map.of();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> m = json.readValue(body, Map.class);
      return m;
    } catch (Exception e) {
      return Map.of();
    }
  }
}
