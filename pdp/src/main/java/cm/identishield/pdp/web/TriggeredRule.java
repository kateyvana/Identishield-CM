package cm.identishield.pdp.web;

/** Règle déclenchée avec sa contribution au score (EF-11). Vide au mois 2 (aucune règle). */
public record TriggeredRule(String ruleId, int contribution, String explanation) {}
