package cm.identishield.core.mobile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Surface mobile en HTTPS (L7, ADR-036 amendé le 2026-10-23) : second connecteur du cœur, réservé
 * aux routes /mobile/v1/*. TLS simple, serveur authentifié par la feuille {@code core-mobile}
 * (jamais {@code CN=core}, l'identité client du cœur auprès du PDP). Désactivé par défaut : la
 * feuille est émise par l'infrastructure (L7-b) ; sans elle, le connecteur n'est pas ouvert, et les
 * routes mobiles ne sont joignables NULLE PART (jamais en clair, voir {@link MobilePortFilter}).
 *
 * @param enabled ouvre le connecteur HTTPS
 * @param port port du connecteur (8443 dans le contrat)
 * @param certPath certificat PEM de la feuille core-mobile
 * @param keyPath clé privée PEM de la feuille core-mobile
 */
@ConfigurationProperties("core.mobile-tls")
public record MobileTlsProperties(boolean enabled, int port, String certPath, String keyPath) {}
