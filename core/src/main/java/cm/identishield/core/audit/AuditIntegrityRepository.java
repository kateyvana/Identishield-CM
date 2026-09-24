package cm.identishield.core.audit;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lecture SEULE de l'état d'intégrité du journal d'audit (EF-34), en appelant la vérification
 * RÉELLE de T2-06 — les fonctions {@code pdp.audit_verify} (couche A, chaînage) et {@code
 * pdp.audit_anchor_check} (couche B, ancre chain_head). La logique de vérification n'est PAS
 * réécrite ici : on l'INVOQUE et on affiche son verdict.
 *
 * <p>Le cœur est un vérificateur INDÉPENDANT : il n'écrit jamais le journal (seul {@code
 * isc_pdp_app} le fait). Son rôle {@code isc_core_app} a reçu un accès STRICTEMENT en lecture
 * (SELECT + EXECUTE) au schéma {@code pdp} — aucun droit d'écriture, l'inaltérabilité du journal
 * est préservée (init.sql).
 */
@Repository
public class AuditIntegrityRepository {

  private final JdbcTemplate jdbc;

  public AuditIntegrityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public AuditIntegrity check() {
    // Couche A — chaînage : audit_verify(0) ne renvoie QUE les entrées anomales, ordonnées par
    // index.
    List<Long> broken =
        jdbc.queryForList(
            "SELECT chain_index FROM pdp.audit_verify(0) ORDER BY chain_index", Long.class);
    // Couche B — ancre chain_head (couvre la suppression du dernier enregistrement / la
    // réécriture).
    return jdbc.queryForObject(
        "SELECT actual_count, actual_last_seq, head_matches FROM pdp.audit_anchor_check()",
        (rs, i) -> {
          long count = rs.getLong("actual_count");
          long lastSeq = rs.getLong("actual_last_seq");
          boolean anchorMatches = rs.getBoolean("head_matches");
          boolean intact = broken.isEmpty() && anchorMatches;
          Long firstBroken = broken.isEmpty() ? null : broken.get(0);
          return new AuditIntegrity(intact, count, lastSeq, anchorMatches, firstBroken, broken);
        });
  }
}
