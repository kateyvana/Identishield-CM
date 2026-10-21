package cm.identishield.pdp.device;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paramètres de l'empreinte PBKDF2-HMAC-SHA256 du code de récupération (ADR-041 §5, ES-18), nommés
 * dans la configuration du PDP, jamais en littéraux. Le code n'a que CODE_LENGTH caractères : c'est
 * le coût de chaque essai (itérations) qui protège l'empreinte d'une recherche exhaustive.
 *
 * @param iterations itérations PBKDF2 (OWASP : au moins 600 000 pour HMAC-SHA256)
 * @param saltBytes taille du sel aléatoire propre à chaque code, en octets
 * @param keyBits taille de l'empreinte dérivée, en bits
 */
@ConfigurationProperties("isc.recovery-code.pbkdf2")
public record RecoveryCodeProperties(int iterations, int saltBytes, int keyBits) {}
