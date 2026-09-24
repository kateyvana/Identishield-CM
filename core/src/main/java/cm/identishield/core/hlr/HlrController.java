package cm.identishield.core.hlr;

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

  /**
   * Lecture de l'état d'abonné du HLR : l'IMSI ACTIF d'un numéro et l'horodatage de son dernier
   * changement. C'est une donnée LÉGITIME (état du sujet), pas une infrastructure d'attaque : la
   * console opérateur (T2-17) l'affiche pour montrer la bascule d'IMSI d'AC-02 à mesure qu'elle
   * survient. Aucune écriture, aucun motif, aucune chronologie — la chronologie de l'attaque, elle,
   * ne transite jamais par le cœur (ADR-034) et vit sur isc-sim.
   */
  // @implements EF-08
  @GetMapping("/hlr")
  public ResponseEntity<Map<String, Object>> subscriber(@RequestParam String msisdn) {
    if (blank(msisdn)) {
      return error(HttpStatus.BAD_REQUEST, "msisdn est obligatoire");
    }
    Optional<String> current = hlr.currentImsi(msisdn);
    if (current.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "numero inconnu du HLR : " + msisdn);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msisdn", msisdn);
    body.put("currentImsi", current.get());
    hlr.lastChangeAt(msisdn).ifPresent(at -> body.put("updatedAt", at.toString()));
    return ResponseEntity.ok(body);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
