package cm.identishield.core.otp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surface HTTP de l'OTP (EF-03), sur le réseau interne isc-net. Trois points, qui reproduisent le
 * cycle réel d'un OTP par SMS :
 *
 * <ul>
 *   <li>{@code POST /otp/issue} : émet un OTP vers l'IMSI COURANT du numéro et le LIVRE dans la
 *       boîte SIM de cet IMSI. Renvoie l'IMSI cible, JAMAIS le code (le code ne s'obtient qu'en
 *       lisant la boîte).
 *   <li>{@code GET /sim/inbox?imsi=...} : lit le code livré à un IMSI — l'INTERCEPTION par qui
 *       détient la SIM. Après un SIM swap, c'est l'attaquant (AC-02).
 *   <li>{@code POST /otp/verify} : consomme un OTP (usage unique).
 * </ul>
 *
 * <p>Aucun jeton d'outil au mois 2 (différé, ADR/§ mois 4) : comme /hlr/reassign, l'appel est
 * ouvert sur le réseau interne. C'est délibéré pour la démonstration : l'outil attaquant ne fait
 * que ce qu'un porteur de SIM peut faire.
 *
 * <p><b>Revue ligne à ligne requise</b> : credential à usage unique (OTP), et canal d'interception.
 */
// @implements EF-03
@RestController
public class OtpController {

  private final OtpService otp;
  private final SimInbox sim;

  public OtpController(OtpService otp, SimInbox sim) {
    this.otp = otp;
    this.sim = sim;
  }

  @PostMapping("/otp/issue")
  public ResponseEntity<Map<String, Object>> issue(@RequestBody MsisdnRequest req) {
    if (blank(req.msisdn())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn obligatoire");
    }
    try {
      OtpEmission em = otp.issue(req.msisdn());
      sim.deliver(em.targetImsi(), em.code()); // livraison SMS simulée vers l'IMSI courant
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("msisdn", em.msisdn());
      body.put("targetImsi", em.targetImsi());
      body.put("delivered", true);
      return ResponseEntity.ok(body);
    } catch (IllegalStateException e) {
      return error(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @GetMapping("/sim/inbox")
  public ResponseEntity<Map<String, Object>> inbox(@RequestParam String imsi) {
    Optional<String> code = sim.read(imsi);
    if (code.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "aucun message pour l'IMSI " + imsi);
    }
    return ResponseEntity.ok(Map.of("imsi", imsi, "code", code.get()));
  }

  @PostMapping("/otp/verify")
  public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest req) {
    if (blank(req.msisdn()) || blank(req.code())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn et code obligatoires");
    }
    OtpOutcome outcome = otp.verify(req.msisdn(), req.code());
    return ResponseEntity.ok(Map.of("outcome", outcome.name()));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  /** Corps portant un seul numéro. */
  public record MsisdnRequest(String msisdn) {}

  /** Corps de vérification : numéro + code à consommer. */
  public record VerifyRequest(String msisdn, String code) {}
}
