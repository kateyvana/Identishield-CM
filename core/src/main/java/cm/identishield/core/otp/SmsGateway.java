package cm.identishield.core.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Passerelle SMS du cœur (EF-03). Le cœur (serveur d'authentification) émet l'OTP puis le REMET au
 * réseau SMS simulé ({@code isc-sim}) par un appel SORTANT cœur→réseau, exactement comme un serveur
 * d'auth remet un SMS à une passerelle. Le cœur ne relit JAMAIS ce qu'il a remis : l'IMSI
 * destinataire lit son SMS sur {@code isc-sim}, pas ici. Le canal d'interception d'AC-02 vit donc
 * entièrement hors du cœur (ADR-034).
 *
 * <p>URL du réseau via {@code SIM_URL} (défaut {@code http://sim:8080}). HTTP simple sur isc-net
 * interne, sans jeton au mois 2 (comme les autres appels internes).
 */
// @implements EF-03
@Component
public class SmsGateway {

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json;
  private final String simUrl;

  public SmsGateway(ObjectMapper json, @Value("${sim.url:http://sim:8080}") String simUrl) {
    this.json = json;
    this.simUrl = simUrl;
  }

  /**
   * Remet le code OTP au réseau, à destination d'un IMSI. Lève {@link SmsDeliveryException} si le
   * réseau refuse ou est injoignable — l'émetteur décide alors quoi renvoyer à l'appelant.
   */
  public void deliver(String imsi, String code) {
    try {
      String body = json.writeValueAsString(Map.of("imsi", imsi, "code", code));
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(simUrl + "/deliver"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
      if (resp.statusCode() != 200) {
        throw new SmsDeliveryException(
            "le reseau SMS a refuse la remise (HTTP " + resp.statusCode() + ")");
      }
    } catch (SmsDeliveryException e) {
      throw e;
    } catch (Exception e) {
      throw new SmsDeliveryException("reseau SMS injoignable : " + e.getMessage());
    }
  }
}
