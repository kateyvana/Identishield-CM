package cm.identishield.core.pdp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du client PDP, chargee depuis la configuration versionnee (application.yml).
 * CAP_FAILCLOSED est un parametre nomme (ISC-TM §7) : il ne figure jamais en litteral dans le code.
 * Aucun secret ici : les chemins pointent vers le volume `certs` monte au runtime.
 */
@ConfigurationProperties("core.pdp")
public record PdpClientProperties(
    String baseUrl,
    String healthPath,
    long timeoutMs,
    String certPath,
    String keyPath,
    String caPath,
    Breaker breaker) {
  // CAP_FAILCLOSED ne vit PAS ici : c'est un parametre nomme, lu du jeu de regles
  // versionne (RuleSet / config/ruleset.yaml), source unique (ES-15).

  /** Seuils du disjoncteur (fait maison, sans dependance : ADR-021 / §4). */
  public record Breaker(int failureThreshold, long cooldownMs) {}
}
