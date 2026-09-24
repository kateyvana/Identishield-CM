package cm.identishield.core.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * État d'intégrité du journal d'audit pour la console (EF-34), sur le réseau interne isc-net.
 *
 * <p>LECTURE SEULE. On appelle la vérification EXISTANTE de T2-06 ({@code pdp.audit_verify} /
 * {@code pdp.audit_anchor_check}) et on renvoie son verdict : chaîne intègre, ou position exacte de
 * la première entrée altérée. Aucune route d'écriture ni de modification du journal n'est exposée —
 * ni ici ni ailleurs dans le cœur pour le compte de la console : le journal est inaltérable, et
 * l'altération de démonstration se fait par le rôle administrateur, JAMAIS par la console.
 */
// @implements EF-34
@RestController
public class AuditIntegrityController {

  private final AuditIntegrityRepository audit;

  public AuditIntegrityController(AuditIntegrityRepository audit) {
    this.audit = audit;
  }

  @GetMapping("/audit/integrity")
  public AuditIntegrity integrity() {
    return audit.check();
  }
}
