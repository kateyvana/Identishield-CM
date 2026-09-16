package cm.identishield.core.transfer;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surface HTTP d'exécution d'une opération (EF-04/EF-05), sur le réseau interne isc-net. {@code
 * POST /operation} exécute une opération de n'importe quel type via {@link TransferService#operate}
 * — le MÊME chemin (donc le même passage /evaluate-avant-commit, ou son contournement en mode sans
 * couche) que les canaux internes. C'est ce que l'outil attaquant appelle pour vider un compte.
 *
 * <p>Aucun jeton d'outil au mois 2 (différé) : ouvert sur le réseau interne, comme /hlr/reassign.
 */
// @implements EF-04
@RestController
public class OperationController {

  private final TransferService transfers;

  public OperationController(TransferService transfers) {
    this.transfers = transfers;
  }

  @PostMapping("/operation")
  public ResponseEntity<Map<String, Object>> operate(@RequestBody OperationRequest req) {
    if (blank(req.msisdn())
        || blank(req.type())
        || blank(req.counterparty())
        || req.amountXaf() <= 0) {
      return error(
          HttpStatus.BAD_REQUEST, "msisdn, type, counterparty et amountXaf (>0) sont obligatoires");
    }
    OperationType type;
    try {
      type = OperationType.valueOf(req.type().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, "type d'opération inconnu : " + req.type());
    }
    String channel = blank(req.channel()) ? "USSD" : req.channel();
    try {
      TransferResult r =
          transfers.operate(req.msisdn(), type, req.amountXaf(), req.counterparty(), channel);
      return ResponseEntity.ok(
          Map.of(
              "state",
              r.state().name(),
              "decisionId",
              r.decisionId(),
              "detail",
              r.detail() == null ? "" : r.detail()));
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

  /** Corps d'une opération : numéro initiateur, type, montant, contrepartie, canal. */
  public record OperationRequest(
      String msisdn, String type, long amountXaf, String counterparty, String channel) {}
}
