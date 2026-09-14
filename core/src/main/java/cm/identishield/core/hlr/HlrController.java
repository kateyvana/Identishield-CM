package cm.identishield.core.hlr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point d'entrée HTTP du HLR pour l'outil agent opérateur (EF-42, UC-11) : {@code isc-hlr-agent}
 * POST ici pour réattribuer une SIM. Ce contrôleur UTILISE {@link HlrService} (T2-10) tel quel — il
 * n'ajoute ni logique de réattribution ni émission d'événement, seulement l'aiguillage HTTP et deux
 * refus : numéro inconnu du HLR, et nouvel IMSI identique à l'IMSI actif.
 *
 * <p>Le système ne distingue PAS un agent honnête d'un agent complice : le même appel, avec les
 * mêmes paramètres, sert à la reconduction légitime et au vol (ADR-011). Aucun mode, aucun drapeau
 * d'intention. Le motif est journalisé par {@link HlrService}, jamais transmis au PDP (ES-16b).
 */
// @implements EF-42
@RestController
public class HlrController {

  private final HlrService hlr;

  public HlrController(HlrService hlr) {
    this.hlr = hlr;
  }

  @PostMapping("/hlr/reassign")
  public ResponseEntity<Map<String, Object>> reassign(@RequestBody HlrReassignRequest req) {
    if (blank(req.msisdn())
        || blank(req.newImsi())
        || blank(req.agentId())
        || blank(req.reason())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn, newImsi, agentId et reason sont obligatoires");
    }
    Optional<String> current = hlr.currentImsi(req.msisdn());
    if (current.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "numero inconnu du HLR : " + req.msisdn());
    }
    if (req.newImsi().equals(current.get())) {
      return error(HttpStatus.CONFLICT, "nouvel IMSI identique a l'IMSI actif : " + req.newImsi());
    }
    ImsiChangeEvent ev = hlr.reassign(req.msisdn(), req.newImsi(), req.agentId(), req.reason());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msisdn", ev.msisdn());
    body.put("oldImsi", ev.oldImsi());
    body.put("newImsi", ev.newImsi());
    body.put("changedAt", ev.changedAt().toString());
    body.put("eventEmitted", true);
    return ResponseEntity.ok(body);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
