package cm.identishield.core.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres PBKDF2 du PIN applicatif (carte B3a, décision 2), lus de la configuration du cœur
 * (application.yml), pas du jeu de règles : ce sont des paramètres de stockage, pas de décision.
 */
@ConfigurationProperties("core.pin.pbkdf2")
public record PinProperties(int iterations, int saltBytes, int keyBits) {}
