package cm.identishield.pdp.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point de santé du PDP, joignable seulement après une poignée de main mTLS réussie
 * (server.ssl.client-auth=need). Prêt PAR CONSTRUCTION (décision du superviseur technique du
 * 2026-10-25) : la réponse est un VRAI aller-retour en base — 200 {@code UP} seulement si le PDP
 * peut décider maintenant, 503 {@code DOWN} sinon. C'est ce que le cœur attend au démarrage ({@code
 * PdpClient.warmup}) et ce que le disjoncteur sonde. Sert aussi de cible à la mesure de latence
 * transport (le surcoût mTLS est indépendant de l'endpoint).
 */
@RestController
public class HealthController {

  // @implements ES-11

  private final DatabaseReadiness database;

  public HealthController(DatabaseReadiness database) {
    this.database = database;
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    boolean up = database.ready();
    return ResponseEntity.status(up ? 200 : 503)
        .body(Map.of("status", up ? "UP" : "DOWN", "service", "isc-pdp"));
  }
}
