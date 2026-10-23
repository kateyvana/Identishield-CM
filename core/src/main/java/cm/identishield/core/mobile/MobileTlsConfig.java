package cm.identishield.core.mobile;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Surface mobile en HTTPS (L7-a). Ajoute au Tomcat du cœur un second connecteur, TLS simple, sur le
 * port {@code core.mobile-tls.port}, avec la feuille serveur {@code core-mobile} en PEM ; le port
 * 8080 interne est inchangé. Le connecteur écoute sur toutes les interfaces DU CONTENEUR (le test
 * de contrat l'atteint depuis isc-net) ; la restriction à l'adresse Tailscale est la publication du
 * port côté hôte, dans le compose (carte L7-b, couloir A).
 *
 * <p>UN SEUL INTERRUPTEUR, {@code core.mobile-tls.enabled}, commande le connecteur ET le tri des
 * routes par port ({@link MobilePortFilter}). Éteint (défaut) : rien ne change. Allumé sans
 * certificat ou sans clé lisible, ou avec un port mobile invalide ou égal au port interne : le cœur
 * ne démarre pas, avec un message qui nomme la cause. Jamais de démarrage partiel.
 */
// @implements ENF-07
// @implements EI-03
@Configuration
@EnableConfigurationProperties(MobileTlsProperties.class)
public class MobileTlsConfig {

  @Bean
  FilterRegistrationBean<MobilePortFilter> mobilePortFilter(MobileTlsProperties tls) {
    FilterRegistrationBean<MobilePortFilter> reg =
        new FilterRegistrationBean<>(new MobilePortFilter(tls));
    reg.addUrlPatterns("/*");
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return reg;
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> mobileTlsConnector(
      MobileTlsProperties tls) {
    return factory -> {
      if (tls.enabled()) {
        factory.addAdditionalTomcatConnectors(connector(tls, factory.getPort()));
      }
    };
  }

  static Connector connector(MobileTlsProperties tls, int internalPort) {
    if (tls.port() < 1 || tls.port() > 65535 || tls.port() == internalPort) {
      throw new IllegalStateException(
          "core.mobile-tls active mais port mobile invalide : "
              + tls.port()
              + " (doit etre distinct du port interne "
              + internalPort
              + ")");
    }
    requireReadable("certificat", tls.certPath());
    requireReadable("cle privee", tls.keyPath());
    Connector connector = new Connector(Http11NioProtocol.class.getName());
    connector.setPort(tls.port());
    connector.setScheme("https");
    connector.setSecure(true);
    Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
    protocol.setSSLEnabled(true);
    SSLHostConfig host = new SSLHostConfig();
    host.setProtocols("TLSv1.2,TLSv1.3");
    SSLHostConfigCertificate certificate =
        new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
    certificate.setCertificateFile(tls.certPath());
    certificate.setCertificateKeyFile(tls.keyPath());
    host.addCertificate(certificate);
    connector.addSslHostConfig(host);
    return connector;
  }

  private static void requireReadable(String what, String path) {
    if (path == null || path.isBlank() || !Files.isReadable(Path.of(path))) {
      throw new IllegalStateException(
          "core.mobile-tls active mais "
              + what
              + " illisible : "
              + path
              + " (feuille core-mobile emise par l'infrastructure, carte L7-b)");
    }
  }
}
