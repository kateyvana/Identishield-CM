package cm.identishield.core.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Délai d'attente des routes /device/* du PDP (contracts/device.yaml), DISTINCT de celui
 * d'/evaluate (core.pdp.timeout-ms) : il est fixé d'après la mesure de la dérivation PBKDF2 du code
 * de récupération, jamais l'inverse (décision de la propriétaire du 2026-10-22).
 */
@ConfigurationProperties("core.device")
public record DeviceClientProperties(long timeoutMs) {}
