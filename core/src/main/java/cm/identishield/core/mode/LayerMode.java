package cm.identishield.core.mode;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Bascule du « mode sans couche » (EF-07), pour la démonstration avant/après. Quand elle est ACTIVE
 * ({@code layerless = true}), la couche de sécurité est DÉSACTIVÉE : le cœur exécute une opération
 * SANS appeler /evaluate — l'état « avant » de la démo, où l'attaque passe. Par défaut la couche
 * est en place ({@code layerless = false}).
 *
 * <p>État en mémoire du processus cœur, jamais persisté : il n'a de sens que pour la démonstration.
 * {@link AtomicBoolean} car lu par le fil web (console) et par le fil des opérations.
 */
// @implements EF-07
@Component
public class LayerMode {

  private final AtomicBoolean layerless = new AtomicBoolean(false);

  /** Vrai si la couche de sécurité est désactivée (mode démo « avant »). */
  public boolean isLayerless() {
    return layerless.get();
  }

  public void setLayerless(boolean value) {
    layerless.set(value);
  }
}
