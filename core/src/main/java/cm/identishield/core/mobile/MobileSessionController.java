package cm.identishield.core.mobile;

import cm.identishield.core.mobile.MobileEnrolment.Answer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes /mobile/v1 de la session (contracts/mobile-api.yaml, carte B4) : défi puis ouverture. Même
 * lecture stricte des corps et mêmes réponses transitoires que {@link MobileEnrolmentController},
 * dont ce contrôleur réutilise les contrôles de forme.
 */
@RestController
@RequestMapping("/mobile/v1")
public class MobileSessionController {

  record SessionRequest(
      String msisdn, String pin, String deviceId, String challenge, String signature) {}

  private final MobileSession session;
  private final MobileEnrolmentController shapes;

  public MobileSessionController(MobileSession session, MobileEnrolmentController shapes) {
    this.session = session;
    this.shapes = shapes;
  }

  @GetMapping("/session/challenge")
  public ResponseEntity<Map<String, Object>> challenge(
      @RequestParam(required = false) String deviceId, HttpServletRequest http) {
    if (!MobileEnrolmentController.isDeviceId(deviceId)
        || MobileEnrolmentController.MALFORMED_INSTALLATION.equals(
            MobileEnrolmentController.installationId(http))) {
      return MobileEnrolmentController.invalid();
    }
    return shapes.answer(session.challenge(deviceId));
  }

  @PostMapping("/session")
  public ResponseEntity<Map<String, Object>> open(
      @RequestBody String body, HttpServletRequest http) {
    SessionRequest r = shapes.read(body, SessionRequest.class);
    if (r == null
        || !MobileEnrolmentController.isMsisdn(r.msisdn())
        || !MobileEnrolmentController.isPin(r.pin())
        || !MobileEnrolmentController.isDeviceId(r.deviceId())
        || !MobileEnrolmentController.isChallenge(r.challenge())
        || !MobileEnrolmentController.isBase64(r.signature())) {
      return MobileEnrolmentController.invalid();
    }
    Answer a =
        session.open(
            r.msisdn(), r.pin(), r.deviceId(), r.challenge(), r.signature(), http.getRemoteAddr());
    return shapes.answer(a);
  }
}
