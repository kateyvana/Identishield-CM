package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Routes de session (carte B4) : lecture stricte, formes du contrat, Retry-After d'une origine
 * bloquée = secondes restantes (P-11), aucun Retry-After sur un refus définitif.
 *
 * <p>@covers ES-22
 */
class MobileSessionControllerTest {

  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";
  private static final String NONCE = "9f86d081884c7d659a2feaa0c55ad015";

  private final MobileSession service = mock(MobileSession.class);
  private final HttpServletRequest http = mock(HttpServletRequest.class);
  private final MobileSessionController controller =
      new MobileSessionController(
          service,
          new MobileEnrolmentController(
              mock(MobileEnrolment.class),
              new ObjectMapper(),
              new PdpClientProperties(
                  "https://pdp:8443",
                  "/health",
                  800,
                  "c",
                  "k",
                  "a",
                  new PdpClientProperties.Breaker(3, 4_500),
                  20,
                  500)));

  MobileSessionControllerTest() {
    when(http.getRemoteAddr()).thenReturn("100.64.0.9");
  }

  private static String body(String msisdn, String pin, String device, String challenge) {
    return "{\"msisdn\":\""
        + msisdn
        + "\",\"pin\":\""
        + pin
        + "\",\"deviceId\":\""
        + device
        + "\",\"challenge\":\""
        + challenge
        + "\",\"signature\":\"c2ln\"}";
  }

  @Test
  void badShapes_areRequestInvalid_beforeAnyWork() {
    for (String b :
        new String[] {
          body("67700012", "4096", DEVICE, NONCE),
          body("677000123", "40a6", DEVICE, NONCE),
          body("677000123", "4096", "pas-un-uuid", NONCE),
          body("677000123", "4096", DEVICE, "trop court"),
          "{\"msisdn\":\"677000123\",\"pin\":\"4096\"}",
          "{\"msisdn\":\"677000123\",\"pin\":\"4096\",\"deviceId\":\""
              + DEVICE
              + "\",\"challenge\":\""
              + NONCE
              + "\",\"signature\":\"c2ln\",\"extra\":1}",
          "null"
        }) {
      ResponseEntity<Map<String, Object>> r = controller.open(b, http);
      assertEquals(400, r.getStatusCode().value(), b);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody(), b);
    }
    assertEquals(400, controller.challenge(null, http).getStatusCode().value());
    assertEquals(400, controller.challenge("x", http).getStatusCode().value());
    verifyNoInteractions(service);
  }

  @Test
  void aLockedOrigin_gets429_withTheRemainingSecondsAsRetryAfter() {
    when(service.open("677000123", "4096", DEVICE, NONCE, "c2ln", "100.64.0.9"))
        .thenReturn(Answer.throttled(37));
    ResponseEntity<Map<String, Object>> r =
        controller.open(body("677000123", "4096", DEVICE, NONCE), http);
    assertEquals(429, r.getStatusCode().value());
    assertEquals("37", r.getHeaders().getFirst("Retry-After"));
    assertEquals(Map.of("reason", "THROTTLED"), r.getBody());
  }

  @Test
  void aRefusal_carriesNoRetryAfter_andTheChallengeIsPassedThrough() {
    when(service.open("677000123", "4096", DEVICE, NONCE, "c2ln", "100.64.0.9"))
        .thenReturn(Answer.refused(401, "CREDENTIALS_INVALID"));
    ResponseEntity<Map<String, Object>> r =
        controller.open(body("677000123", "4096", DEVICE, NONCE), http);
    assertEquals(401, r.getStatusCode().value());
    assertNull(r.getHeaders().getFirst("Retry-After"));

    when(service.challenge(DEVICE)).thenReturn(Answer.ok(Map.of("challenge", NONCE)));
    assertEquals(Map.of("challenge", NONCE), controller.challenge(DEVICE, http).getBody());
  }
}
