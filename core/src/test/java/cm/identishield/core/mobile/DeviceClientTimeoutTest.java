package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * D-B7-1 (passe B7 du 2026-10-25, décision du superviseur technique) : un appel {@code /device/*}
 * qui dépasse {@code core.device.timeout-ms} laisse UNE ligne WARN avec la route et la durée, et
 * rien d'autre (aucun identifiant de compte ou d'appareil, aucun corps). Sans cette ligne, la cause
 * du 503 rendu à l'application était introuvable dans les journaux (qualification du 2026-10-25).
 */
class DeviceClientTimeoutTest {

  private static final String ACCOUNT = "5bee60dd-3ea3-4f73-9767-a110f48800f8";

  /**
   * Cas « au-delà » : délai client court contre un serveur qui dort bien plus longtemps. Cas « dans
   * le délai » : délai client LARGE, car il couvre aussi la connexion d'un HttpClient neuf sur une
   * JVM froide, qui sous charge dépasse 150 ms (relecture du couloir A, 2026-10-25 : statut 0
   * observé une fois sous mutation avec un délai unique de 150 ms).
   */
  private static final long SHORT_TIMEOUT_MS = 150;

  private static final long SERVER_SLEEP_MS = 2000;
  private static final long WIDE_TIMEOUT_MS = 5000;

  private HttpServer server;
  private volatile long serverDelayMs;
  private ListAppender<ILoggingEvent> warnings;
  private Logger logger;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/device/state",
        exchange -> {
          try {
            Thread.sleep(serverDelayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "{\"state\":\"NONE\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    logger = (Logger) LoggerFactory.getLogger(DeviceClient.class);
    warnings = new ListAppender<>();
    warnings.start();
    logger.addAppender(warnings);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(warnings);
    server.stop(0);
  }

  private DeviceClient client(long timeoutMs) throws Exception {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new DeviceClient(base, timeoutMs, SSLContext.getDefault(), new ObjectMapper());
  }

  private List<ILoggingEvent> warnLines() {
    return warnings.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
  }

  @Test
  void aCallBeyondTheTimeout_isUnreachable_andLeavesOneWarnWithRouteAndDurationOnly()
      throws Exception {
    serverDelayMs = SERVER_SLEEP_MS;
    DeviceClient.Reply reply = client(SHORT_TIMEOUT_MS).state(ACCOUNT);

    assertEquals(DeviceClient.Reply.UNREACHABLE, reply.status());
    List<ILoggingEvent> warns = warnLines();
    assertEquals(1, warns.size(), "exactement une ligne WARN");
    String line = warns.get(0).getFormattedMessage();
    assertTrue(line.contains("/device/state"), line);
    assertTrue(line.contains("délai dépassé"), line);
    assertTrue(line.matches(".*\\b\\d+ ms.*"), "la durée en millisecondes : " + line);
    assertFalse(line.contains(ACCOUNT), "jamais d'identifiant dans la ligne : " + line);
    assertFalse(line.contains("accountId="), "jamais la requête complète : " + line);
  }

  @Test
  void aCallWithinTheTimeout_leavesNoWarn() throws Exception {
    serverDelayMs = 0;
    DeviceClient.Reply reply = client(WIDE_TIMEOUT_MS).state(ACCOUNT);

    assertEquals(200, reply.status());
    assertEquals("NONE", reply.body().get("state"));
    assertTrue(warnLines().isEmpty(), "aucune ligne WARN pour un appel dans le délai");
  }
}
