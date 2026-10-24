package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import cm.identishield.core.pdp.PdpClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Routes du quotidien (carte B6) : la session d'abord, la forme ensuite ; un appelant sans jeton
 * vivant ne reçoit que 401 ; seule GET /alerts est une requête automatique (L-B4-2).
 *
 * <p>@covers ES-15
 *
 * <p>@covers ES-22
 */
class MobileDailyControllerTest {

  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000123");
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";
  private static final String NONCE = "9f86d081884c7d659a2feaa0c55ad015";
  private static final String TRANSFER = "11111111-2222-4333-8444-555555555555";
  private static final String BEARER_VALUE = "jeton-de-session";
  private static final LiveSession LIVE = new LiveSession(ACCOUNT, DEVICE);

  private final SessionTokens tokens = mock(SessionTokens.class);
  private final MobileDaily daily = mock(MobileDaily.class);
  private final HttpServletRequest http = mock(HttpServletRequest.class);
  private final MobileDailyController controller =
      new MobileDailyController(
          new MobileSessions(tokens),
          daily,
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
                  new PdpClientProperties.Breaker(3, 4_500))));

  private void bearer(String token) {
    when(http.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
  }

  private void alive() {
    bearer(BEARER_VALUE);
    when(tokens.touch(eq(BEARER_VALUE), anyBoolean())).thenReturn(Optional.of(LIVE));
  }

  private static String cancelBody(String nonce, String signature) {
    return "{\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
  }

  @Test
  void noBearer_everyRouteIs401_andNothingElseIsConsulted() {
    for (String header : new String[] {null, "Basic abc", "Bearer ", "jeton"}) {
      when(http.getHeader("Authorization")).thenReturn(header);
      List<ResponseEntity<Map<String, Object>>> answers =
          List.of(
              controller.nonce(http),
              controller.account(http),
              controller.transactions(http),
              controller.holder("677001101", http),
              controller.cancel(TRANSFER, cancelBody(NONCE, "c2ln"), http),
              controller.alerts(http));
      for (ResponseEntity<Map<String, Object>> r : answers) {
        assertEquals(401, r.getStatusCode().value(), String.valueOf(header));
        assertEquals(Map.of("reason", "CREDENTIALS_INVALID"), r.getBody());
      }
    }
    verifyNoInteractions(daily);
    // « jeton » sans préfixe Bearer n'est jamais présenté au magasin de sessions.
    verify(tokens, org.mockito.Mockito.never()).touch(eq("jeton"), anyBoolean());
  }

  @Test
  void deadToken_is401_evenWithABadlyShapedRequest() {
    bearer(BEARER_VALUE);
    when(tokens.touch(eq(BEARER_VALUE), anyBoolean())).thenReturn(Optional.empty());
    ResponseEntity<Map<String, Object>> r = controller.cancel("pas-un-uuid", "null", http);
    assertEquals(401, r.getStatusCode().value());
    verifyNoInteractions(daily);
  }

  @Test
  void alerts_areTheOnlyAutomaticRequest_everythingElseIsUserActivity() {
    alive();
    when(daily.alerts(LIVE)).thenReturn(Answer.ok(Map.of("items", List.of())));
    when(daily.nonce(LIVE)).thenReturn(Answer.ok(Map.of("nonce", NONCE)));
    when(daily.transactions(LIVE)).thenReturn(Answer.ok(Map.of("items", List.of())));
    when(daily.holder(LIVE, "677001101")).thenReturn(Answer.ok(Map.of()));
    when(daily.account(LIVE)).thenReturn(Answer.ok(Map.of()));
    controller.alerts(http);
    verify(tokens).touch(BEARER_VALUE, false);
    controller.nonce(http);
    controller.account(http);
    controller.transactions(http);
    controller.holder("677001101", http);
    verify(tokens, org.mockito.Mockito.times(4)).touch(BEARER_VALUE, true);
  }

  @Test
  void holder_badMsisdn_is400_afterTheSessionWasTouched() {
    alive();
    for (String m : new String[] {"67700", "6770011011", "677001101x", ""}) {
      ResponseEntity<Map<String, Object>> r = controller.holder(m, http);
      assertEquals(400, r.getStatusCode().value(), m);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody());
    }
    verifyNoInteractions(daily);
  }

  private static String transferBody(String type, String amount, String beneficiary, String pin) {
    return "{\"type\":\""
        + type
        + "\",\"amountXaf\":"
        + amount
        + ",\"beneficiaryId\":\""
        + beneficiary
        + "\",\"nonce\":\""
        + NONCE
        + "\",\"signature\":\"c2ln\",\"pin\":\""
        + pin
        + "\"}";
  }

  @Test
  void transfer_badShapes_are400_beforeAnyWork_signatureMayBeAbsentButNotMalformed() {
    alive();
    for (String b :
        new String[] {
          transferBody("CASH_OUT", "5000", "677001101", "4096"),
          transferBody("P2P", "0", "677001101", "4096"),
          transferBody("P2P", "\"5000\"", "677001101", "4096"),
          transferBody("P2P", "5000", "67700", "4096"),
          transferBody("P2P", "5000", "677001101", "40a6"),
          "{\"type\":\"P2P\",\"amountXaf\":5000,\"beneficiaryId\":\"677001101\",\"nonce\":\"court\",\"pin\":\"4096\"}",
          "{\"type\":\"P2P\",\"amountXaf\":5000,\"beneficiaryId\":\"677001101\",\"nonce\":\""
              + NONCE
              + "\",\"signature\":\"pas base64!\",\"pin\":\"4096\"}",
          "{\"type\":\"P2P\",\"amountXaf\":5000,\"beneficiaryId\":\"677001101\",\"pin\":\"4096\"}",
          transferBody("P2P", "5000", "677001101", "4096").replace("}", ",\"extra\":1}"),
          "null"
        }) {
      ResponseEntity<Map<String, Object>> r = controller.transfer(b, http);
      assertEquals(400, r.getStatusCode().value(), b);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody(), b);
    }
    verifyNoInteractions(daily);
  }

  @Test
  void transfer_wellFormed_reachesTheService_withTheOrigin_signatureOptional() {
    alive();
    when(http.getRemoteAddr()).thenReturn("100.64.0.9");
    when(daily.transfer(LIVE, 5_000L, "677001101", NONCE, "c2ln", "4096", "100.64.0.9"))
        .thenReturn(Answer.ok(Map.of("transferId", TRANSFER)));
    assertEquals(
        200,
        controller
            .transfer(transferBody("P2P", "5000", "677001101", "4096"), http)
            .getStatusCode()
            .value());
    when(daily.transfer(LIVE, 5_000L, "677001101", NONCE, null, "4096", "100.64.0.9"))
        .thenReturn(Answer.ok(Map.of("transferId", TRANSFER)));
    String unsigned =
        "{\"type\":\"P2P\",\"amountXaf\":5000,\"beneficiaryId\":\"677001101\",\"nonce\":\""
            + NONCE
            + "\",\"pin\":\"4096\"}";
    assertEquals(200, controller.transfer(unsigned, http).getStatusCode().value());
  }

  @Test
  void cancel_badShapes_are400_beforeAnyVerification() {
    alive();
    for (String[] c :
        new String[][] {
          {"pas-un-uuid", cancelBody(NONCE, "c2ln")},
          {TRANSFER, cancelBody("trop court", "c2ln")},
          {TRANSFER, cancelBody(NONCE, "pas base64!")},
          {TRANSFER, "{\"nonce\":\"" + NONCE + "\"}"},
          {TRANSFER, "{\"nonce\":\"" + NONCE + "\",\"signature\":\"c2ln\",\"extra\":1}"},
          {TRANSFER, "null"},
          {TRANSFER, ""}
        }) {
      ResponseEntity<Map<String, Object>> r = controller.cancel(c[0], c[1], http);
      assertEquals(400, r.getStatusCode().value(), c[0] + " " + c[1]);
      assertEquals(Map.of("reason", "REQUEST_INVALID"), r.getBody());
    }
    verifyNoInteractions(daily);
  }

  @Test
  void cancel_wellFormed_reachesTheService_withTheSessionAndTheExactFields() {
    alive();
    when(daily.cancel(LIVE, TRANSFER, NONCE, "c2ln"))
        .thenReturn(Answer.ok(Map.of("transferId", TRANSFER, "status", "CANCELLED")));
    ResponseEntity<Map<String, Object>> r =
        controller.cancel(TRANSFER, cancelBody(NONCE, "c2ln"), http);
    assertEquals(200, r.getStatusCode().value());
    assertEquals(Map.of("transferId", TRANSFER, "status", "CANCELLED"), r.getBody());
  }

  @Test
  void transientAnswers_carryRetryAfter() {
    alive();
    when(daily.nonce(LIVE)).thenReturn(Answer.unavailable());
    ResponseEntity<Map<String, Object>> r = controller.nonce(http);
    assertEquals(503, r.getStatusCode().value());
    org.junit.jupiter.api.Assertions.assertTrue(
        Integer.parseInt(r.getHeaders().getFirst("Retry-After")) >= 1, "Retry-After ≥ 1 s");
    verify(daily).nonce(any());
    verify(daily, org.mockito.Mockito.never()).alerts(any());
  }
}
