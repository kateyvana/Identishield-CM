package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Interrupteur ÉTEINT (défaut) : L7-a est INERTE (décision de la propriétaire du 2026-10-23). Les
 * routes mobiles répondent comme avant, sur le port interne ; aucune régression tant que la feuille
 * core-mobile (L7-b) n'est pas livrée. Le PIN en clair reste la limite déclarée L7.
 *
 * <p>@covers EI-03
 */
@SpringBootTest(
    classes = MobileTlsTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {MobileTlsTest.NO_CORE_CONFIG, "core.mobile-tls.enabled=false"})
class MobileTlsOffTest {

  @LocalServerPort private int internalPort;

  private int get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + internalPort + path)).build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void switchedOff_nothingChanges_mobileAndInternalRoutesAnswerOnTheInternalPort()
      throws Exception {
    assertEquals(200, get("/mobile/v1/sonde"));
    assertEquals(200, get("/interne"));
  }
}
