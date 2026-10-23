package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preuve rouge/vert de L7-a (ADR-036, point 5) sur un VRAI Tomcat à deux ports, interrupteur
 * ALLUMÉ, avec un certificat fabriqué au moment du test (keytool du JDK, SAN localhost et 127.0.0.1
 * : le nom composé par le test est vérifié, comme le fera l'APK). Aucune clé dans le dépôt.
 *
 * <p>Sur le port HTTPS : seules les routes /mobile/v1/* répondent ; la racine, /error, /actuator/*,
 * une route interne et une tentative de traversée sont refusées (403 motivé). Sur le port interne :
 * les routes /mobile/v1/* sont refusées, les routes internes passent. Aucune page d'erreur ne
 * laisse passer de trace.
 *
 * <p>@covers ENF-07
 *
 * <p>@covers EI-03
 */
@SpringBootTest(
    classes = MobileTlsTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Aucune configuration du cœur (son application.yml importe /config/ruleset.yaml, absent hors
    // conteneur) : la preuve ne dépend que de ses propres propriétés.
    properties = MobileTlsTest.NO_CORE_CONFIG)
class MobileTlsTest {

  static final String NO_CORE_CONFIG =
      "spring.config.location=optional:classpath:/l7a-sans-config/";

  /** Tiré à chaque exécution : aucun mot de passe, même de test, n'est écrit dans le dépôt. */
  private static final String STORE_PASSWORD = Long.toHexString(new SecureRandom().nextLong());

  private static Path dir;
  private static int mobilePort;

  @LocalServerPort private int internalPort;

  /** Application minimale : la configuration L7-a et une sonde, rien d'autre du cœur. */
  @Configuration
  @ImportAutoConfiguration({
    PropertyPlaceholderAutoConfiguration.class,
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    ErrorMvcAutoConfiguration.class
  })
  @Import({MobileTlsConfig.class, Probe.class})
  static class TestApp {}

  @RestController
  static class Probe {
    @GetMapping("/mobile/v1/sonde")
    String mobile() {
      return "mobile";
    }

    @GetMapping("/interne")
    String internal() {
      return "interne";
    }

    @GetMapping("/")
    String root() {
      return "racine";
    }
  }

  @DynamicPropertySource
  static void tls(DynamicPropertyRegistry registry) throws Exception {
    dir = Files.createTempDirectory("isc-l7a-");
    dir.toFile().deleteOnExit();
    Path store = dir.resolve("t.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "core-mobile",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-sigalg",
                "SHA256withECDSA",
                "-dname",
                "CN=core-mobile",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                STORE_PASSWORD,
                "-keypass",
                STORE_PASSWORD)
            .redirectErrorStream(true)
            .start();
    assertTrue(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0, "keytool");
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (FileInputStream in = new FileInputStream(store.toFile())) {
      ks.load(in, STORE_PASSWORD.toCharArray());
    }
    Certificate cert = ks.getCertificate("core-mobile");
    PrivateKey key = (PrivateKey) ks.getKey("core-mobile", STORE_PASSWORD.toCharArray());
    Path certFile = dir.resolve("core-mobile.crt");
    Path keyFile = dir.resolve("core-mobile.key");
    Files.writeString(certFile, pem("CERTIFICATE", cert.getEncoded()));
    Files.writeString(keyFile, pem("PRIVATE KEY", key.getEncoded()));
    for (Path p : List.of(store, certFile, keyFile)) {
      p.toFile().deleteOnExit();
    }
    try (ServerSocket free = new ServerSocket(0)) {
      mobilePort = free.getLocalPort();
    }
    registry.add("core.mobile-tls.enabled", () -> "true");
    registry.add("core.mobile-tls.port", () -> mobilePort);
    registry.add("core.mobile-tls.cert-path", certFile::toString);
    registry.add("core.mobile-tls.key-path", keyFile::toString);
  }

  private static String pem(String type, byte[] der) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END "
        + type
        + "-----\n";
  }

  /** Client qui ne fait confiance QU'au certificat de test, nom vérifié (comme l'APK). */
  private static HttpClient client() throws Exception {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    try (FileInputStream in = new FileInputStream(dir.resolve("t.p12").toFile())) {
      trust.load(in, STORE_PASSWORD.toCharArray());
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, tmf.getTrustManagers(), null);
    return HttpClient.newBuilder().sslContext(ssl).build();
  }

  private HttpResponse<String> get(String url) throws Exception {
    return client()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private String tls(String path) {
    return "https://localhost:" + mobilePort + path;
  }

  private String plain(String path) {
    return "http://localhost:" + internalPort + path;
  }

  @Test
  void overHttps_theMobileRoutesAnswer() throws Exception {
    HttpResponse<String> r = get(tls("/mobile/v1/sonde"));
    assertEquals(200, r.statusCode());
    assertEquals("mobile", r.body());
  }

  @Test
  void overHttps_noOtherRouteIsReachable_rootErrorActuatorInternalOrTraversal() throws Exception {
    for (String path :
        List.of(
            "/",
            "/error",
            "/actuator/health",
            "/actuator",
            "/interne",
            "/mobile/v1/../interne",
            "/mobile/v1x/sonde",
            "/MOBILE/v1/sonde")) {
      HttpResponse<String> r = get(tls(path));
      assertEquals(403, r.statusCode(), path);
      assertEquals(MobilePortFilter.ONLY_MOBILE_HERE, r.body(), path);
    }
  }

  @Test
  void inCleartext_theMobileRoutesAreRefused_theInternalRoutesStillAnswer() throws Exception {
    HttpResponse<String> mobile = get(plain("/mobile/v1/sonde"));
    assertEquals(403, mobile.statusCode());
    assertEquals(MobilePortFilter.MOBILE_ONLY_OVER_TLS, mobile.body());

    HttpResponse<String> internal = get(plain("/interne"));
    assertEquals(200, internal.statusCode());
    assertEquals("interne", internal.body());
  }

  @Test
  void anUnknownMobileRoute_overHttps_leaksNoTrace() throws Exception {
    HttpResponse<String> r = get(tls("/mobile/v1/inexistante"));
    assertEquals(404, r.statusCode());
    for (String leak : List.of("trace", "exception", "Exception", "at cm.", "at org.")) {
      assertFalse(r.body().contains(leak), "la page d'erreur laisse passer : " + leak);
    }
  }
}
