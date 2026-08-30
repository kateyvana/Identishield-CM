package cm.identishield.pdp.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point de sante minimal du PDP. C'est le SEUL endpoint au mois 2 : /evaluate est T2-05. Il n'est
 * joignable qu'apres une poignee de main mTLS reussie (server.ssl.client-auth=need), donc son acces
 * prouve deja l'authentification du client. Sert aussi de cible a la mesure de latence transport
 * (le surcout mTLS est independant de l'endpoint).
 */
@RestController
public class HealthController {

  // @implements ES-11

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "isc-pdp");
  }
}
