package cm.identishield.pdp.device;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de l'empreinte PBKDF2-HMAC-SHA256 du code de récupération (ADR-041 §5, ES-18), nommés
 * dans la configuration du PDP, jamais en littéraux. Ce qui protège le code est le comptage des
 * essais en ligne (ES-21) et le second facteur (PIN), pas le nombre d'itérations (ADR-042).
 *
 * @param iterations itérations PBKDF2, fixées par un budget de latence (ADR-042), pas par la
 *     recommandation pour mots de passe
 * @param saltBytes taille du sel aléatoire propre à chaque code, en octets
 * @param keyBits taille de l'empreinte dérivée, en bits
 */
@ConfigurationProperties("isc.recovery-code.pbkdf2")
public record RecoveryCodeProperties(int iterations, int saltBytes, int keyBits) {}
