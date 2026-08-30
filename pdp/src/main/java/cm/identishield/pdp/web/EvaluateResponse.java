package cm.identishield.pdp.web;

import java.util.List;

/**
 * Réponse POST /evaluate (EF-11, contrats/evaluate.yaml). Au mois 2 le PDP ne porte aucune règle :
 * décision ALLOW, score 0, aucune règle déclenchée, aucun veto. La {@code ruleSetVersion} vient du
 * jeu de règles versionné (T2-03) ; {@code evaluatedAt} de l'horloge injectée (UTC Z) ; {@code
 * latencyMs} mesuré.
 */
public record EvaluateResponse(
    String decision,
    int score,
    String requiredAction,
    List<TriggeredRule> triggeredRules,
    String vetoApplied,
    String decisionId,
    String ruleSetVersion,
    String evaluatedAt,
    long latencyMs) {}
