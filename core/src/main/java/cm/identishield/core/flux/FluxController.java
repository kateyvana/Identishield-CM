package cm.identishield.core.flux;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flux des transactions pour la console (EF-30). Lecture seule, HTTP simple sur le réseau interne
 * isc-net (la console est le seul lecteur ; jamais exposé à l'hôte directement). La console
 * interroge cet endpoint en temps réel (polling &lt; 2 s).
 */
// @implements EF-30
@RestController
public class FluxController {

  private final FluxRepository flux;

  public FluxController(FluxRepository flux) {
    this.flux = flux;
  }

  @GetMapping("/flux")
  public List<FluxEntry> recent(@RequestParam(defaultValue = "50") int limit) {
    return flux.recent(Math.min(Math.max(limit, 1), 200));
  }
}
