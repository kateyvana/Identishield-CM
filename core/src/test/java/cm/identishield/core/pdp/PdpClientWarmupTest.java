package cm.identishield.core.pdp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

/**
 * Prêt par construction (décision du superviseur technique du 2026-10-25) : au démarrage, le cœur
 * attend que {@code /health} du PDP réponde 200, en réessais BORNÉS ({@code warmup-attempts} ×
 * {@code warmup-delay-ms}, configuration versionnée), et ne bloque jamais son propre démarrage : il
 * journalise « PDP prêt » ou « PDP non prêt après N essais » et continue.
 *
 * <p>@covers ES-11 ; @covers EI-01
 */
class PdpClientWarmupTest {

  private final List<Long> sleeps = new ArrayList<>();
  private final PdpClient.Sleeper recordingSleeper = sleeps::add;

  private static PdpClient.Probe statuses(int... codes) {
    IntSupplier next =
        new IntSupplier() {
          int i;

          @Override
          public int getAsInt() {
            return codes[Math.min(i++, codes.length - 1)];
          }
        };
    return () -> {
      int c = next.getAsInt();
      if (c < 0) {
        throw new java.net.ConnectException("refused");
      }
      return c;
    };
  }

  @Test
  void aPdpReadyAtOnce_isSeenAtTheFirstAttempt_withoutWaiting() {
    assertEquals(1, PdpClient.awaitReady(statuses(200), 20, 500, recordingSleeper));
    assertEquals(List.of(), sleeps);
  }

  @Test
  void connectionRefusedThen503Then200_isReadyAtTheThirdAttempt_afterTwoWaits() {
    assertEquals(3, PdpClient.awaitReady(statuses(-1, 503, 200), 20, 500, recordingSleeper));
    assertEquals(List.of(500L, 500L), sleeps);
  }

  @Test
  void aPdpNeverReady_givesUpAfterTheConfiguredAttempts_andNeverThrows() {
    assertEquals(-1, PdpClient.awaitReady(statuses(-1), 4, 250, recordingSleeper));
    assertEquals(List.of(250L, 250L, 250L), sleeps, "N essais, N-1 attentes");
  }

  @Test
  void anInterruptedWait_stopsAtOnce_andKeepsTheInterruptFlag() {
    PdpClient.Sleeper interrupting =
        ms -> {
          throw new InterruptedException();
        };
    assertEquals(-1, PdpClient.awaitReady(statuses(503), 20, 500, interrupting));
    assertEquals(true, Thread.interrupted(), "drapeau d'interruption conservé (puis effacé ici)");
  }
}
