package cm.identishield.core.ussd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surface HTTP du canal USSD (EF-06), sur le réseau interne isc-net. Une passerelle USSD POSTe ici
 * : {@code POST /ussd/dial} ouvre une session, {@code POST /ussd/input} fait avancer la machine à
 * états (montant → bénéficiaire → confirmation) jusqu'à l'exécution — le fil {@code USSD →
 * transferP2P → /evaluate avant commit}. Ce contrôleur n'ajoute AUCUNE logique : il expose {@link
 * UssdService} tel quel, sur le même {@code operate()} que les canaux internes.
 *
 * <p>Il remplace l'ancien endpoint générique {@code POST /operation} (« exécute n'importe quelle
 * opération sur n'importe quel compte ») : c'est le VRAI canal (EF-06) qu'un attaquant emprunte,
 * conforme à AC-02 (« authentification USSD »), pas une porte générique.
 *
 * <p>Aucun jeton au mois 2 (différé) : ouvert sur le réseau interne, comme /hlr/reassign.
 */
// @implements EF-06
@RestController
public class UssdController {

  private final UssdService ussd;

  public UssdController(UssdService ussd) {
    this.ussd = ussd;
  }

  @PostMapping("/ussd/dial")
  public ResponseEntity<Map<String, Object>> dial(@RequestBody DialRequest req) {
    if (blank(req.msisdn())) {
      return error(HttpStatus.BAD_REQUEST, "msisdn obligatoire");
    }
    UssdService.Interaction it = ussd.dial(req.msisdn());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", it.sessionId().toString());
    body.put("message", it.response().message());
    body.put("sessionOpen", it.response().sessionOpen());
    return ResponseEntity.ok(body);
  }

  @PostMapping("/ussd/input")
  public ResponseEntity<Map<String, Object>> input(@RequestBody InputRequest req) {
    if (blank(req.sessionId()) || req.text() == null) {
      return error(HttpStatus.BAD_REQUEST, "sessionId et text obligatoires");
    }
    UUID id;
    try {
      id = UUID.fromString(req.sessionId());
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, "sessionId invalide");
    }
    UssdResponse r = ussd.input(id, req.text());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", r.message());
    body.put("sessionOpen", r.sessionOpen());
    return ResponseEntity.ok(body);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  /** Corps d'ouverture de session : le numéro qui compose. */
  public record DialRequest(String msisdn) {}

  /** Corps d'une saisie : la session et le texte saisi. */
  public record InputRequest(String sessionId, String text) {}
}
