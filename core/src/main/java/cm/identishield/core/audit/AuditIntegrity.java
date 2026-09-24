package cm.identishield.core.audit;

import java.util.List;

/**
 * Verdict d'intégrité du journal d'audit (EF-34), tel que le renvoie la vérification RÉELLE de
 * T2-06.
 *
 * @param intact chaîne intègre (aucune anomalie de chaînage ET ancre chain_head cohérente).
 * @param entryCount nombre d'entrées du journal.
 * @param lastSeq dernière séquence effective.
 * @param anchorMatches l'ancre (chain_head, couche B) correspond à la réalité de la table.
 * @param firstBrokenIndex position exacte de la PREMIÈRE entrée altérée (couche A), ou {@code
 *     null}.
 * @param brokenIndexes tous les index d'entrées altérées, dans l'ordre.
 */
public record AuditIntegrity(
    boolean intact,
    long entryCount,
    long lastSeq,
    boolean anchorMatches,
    Long firstBrokenIndex,
    List<Long> brokenIndexes) {}
