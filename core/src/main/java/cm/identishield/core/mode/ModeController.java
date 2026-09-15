package cm.identishield.core.mode;

import cm.identishield.core.pdp.PdpClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * État et bascule du mode sans couche pour la console (EF-07, EF-36). Lecture seule + une bascule,
 * HTTP simple sur le réseau interne isc-net (comme /flux : la console est le seul lecteur, jamais
 * exposé à l'hôte directement).
 *
 * <ul>
 *   <li>{@code GET /mode} : le mode sans couche et l'état du PDP (disponible / dégradé /
 *       indisponible), dérivé du disjoncteur du client PDP — sans appel réseau supplémentaire.
 *   <li>{@code POST /mode} avec {@code {"layerless": true|false}} : bascule la couche de sécurité.
 * </ul>
 */
// @implements EF-07
// @implements EF-36
@RestController
public class ModeController {

  private final LayerMode layerMode;
  private final PdpClient pdp;

  public ModeController(LayerMode layerMode, PdpClient pdp) {
    this.layerMode = layerMode;
    this.pdp = pdp;
  }

  @GetMapping("/mode")
  public ModeStatus status() {
    return new ModeStatus(layerMode.isLayerless(), pdpStateLabel());
  }

  @PostMapping("/mode")
  public ModeStatus toggle(@RequestBody ModeToggle body) {
    layerMode.setLayerless(body.layerless());
    return status();
  }

  private String pdpStateLabel() {
    return switch (pdp.circuitState()) {
      case CLOSED -> "DISPONIBLE";
      case HALF_OPEN -> "DEGRADE";
      case OPEN -> "INDISPONIBLE";
    };
  }

  /** Corps de la bascule : {@code {"layerless": true|false}}. */
  public record ModeToggle(boolean layerless) {}
}
