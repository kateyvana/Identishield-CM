package cm.identishield.core.mobile;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.mobile.SessionTokens.LiveSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes du quotidien de /mobile/v1 (contracts/mobile-api.yaml, carte B6), toutes derrière le jeton
 * porteur : la session est touchée d'abord (P-09), la forme de la requête contrôlée ensuite, si
 * bien qu'un appelant sans session n'apprend rien d'autre que 401 (ES-22). Seule GET /alerts est
 * une requête automatique de l'application ({@code userActivity} faux, L-B4-2).
 */
// @implements ES-15
@RestController
@RequestMapping("/mobile/v1")
public class MobileDailyController {

  record CancelRequest(String nonce, String signature) {}

  private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
  private static final Pattern UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final MobileSessions sessions;
  private final MobileDaily daily;
  private final MobileEnrolmentController shapes;

  public MobileDailyController(
      MobileSessions sessions, MobileDaily daily, MobileEnrolmentController shapes) {
    this.sessions = sessions;
    this.daily = daily;
    this.shapes = shapes;
  }

  @PostMapping("/nonce")
  public ResponseEntity<Map<String, Object>> nonce(HttpServletRequest http) {
    return guarded(http, true, daily::nonce);
  }

  @GetMapping("/transactions")
  public ResponseEntity<Map<String, Object>> transactions(HttpServletRequest http) {
    return guarded(http, true, daily::transactions);
  }

  @GetMapping("/beneficiaries/{msisdn}/holder")
  public ResponseEntity<Map<String, Object>> holder(
      @PathVariable String msisdn, HttpServletRequest http) {
    return guarded(
        http,
        true,
        s ->
            MobileEnrolmentController.isMsisdn(msisdn)
                ? daily.holder(s, msisdn)
                : Answer.refused(400, "REQUEST_INVALID"));
  }

  @PostMapping("/transfers/{transferId}/cancel")
  public ResponseEntity<Map<String, Object>> cancel(
      @PathVariable String transferId, @RequestBody String body, HttpServletRequest http) {
    return guarded(
        http,
        true,
        s -> {
          CancelRequest r = shapes.read(body, CancelRequest.class);
          if (r == null
              || !UUID.matcher(transferId).matches()
              || !NONCE.matcher(nullToEmpty(r.nonce())).matches()
              || !MobileEnrolmentController.isBase64(r.signature())) {
            return Answer.refused(400, "REQUEST_INVALID");
          }
          return daily.cancel(s, transferId, r.nonce(), r.signature());
        });
  }

  /** Requête AUTOMATIQUE (L-B4-2) : maintient la session, ne compte pas comme activité. */
  @GetMapping("/alerts")
  public ResponseEntity<Map<String, Object>> alerts(HttpServletRequest http) {
    return guarded(http, false, daily::alerts);
  }

  private ResponseEntity<Map<String, Object>> guarded(
      HttpServletRequest http, boolean userActivity, Function<LiveSession, Answer> route) {
    return shapes.answer(
        sessions
            .authenticate(http, userActivity)
            .map(route)
            .orElseGet(() -> Answer.refused(401, "CREDENTIALS_INVALID")));
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
