package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.core.time.VirtualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P-11 (carte B4) : les échecs de PIN se comptent par ORIGINE réseau ; au max_attempts-ième, cette
 * origine seule attend origin_lock ; une autre origine, et donc le titulaire depuis son appareil,
 * n'est jamais gênée (même motif qu'ES-21) ; un PIN juste efface l'ardoise.
 *
 * <p>@covers ES-22
 */
class PinAttemptsTest {

  private static final Instant T0 = Instant.parse("2026-10-24T08:00:00Z");
  private final VirtualClock clock = new VirtualClock(T0);
  private final PinAttempts attempts = new PinAttempts(clock, 3, Duration.ofMinutes(2));

  @Test
  void theOriginIsLockedAtTheNthFailure_forTheWholeLockDuration_thenStartsOver() {
    attempts.failed("a");
    attempts.failed("a");
    assertEquals(Optional.empty(), attempts.lockedFor("a"), "deux échecs : encore libre");
    attempts.failed("a");
    assertEquals(Optional.of(120L), attempts.lockedFor("a"), "troisième : bloquée 2 min");
    clock.advance(Duration.ofSeconds(119).plusMillis(1));
    assertEquals(Optional.of(1L), attempts.lockedFor("a"), "arrondi au-dessus, jamais 0");
    clock.advance(Duration.ofSeconds(1));
    assertEquals(Optional.empty(), attempts.lockedFor("a"), "blocage échu");
    attempts.failed("a");
    attempts.failed("a");
    assertEquals(Optional.empty(), attempts.lockedFor("a"), "repart de zéro après le blocage");
  }

  @Test
  void anotherOrigin_isNeverAffected_andASuccessClearsTheSlate() {
    for (int i = 0; i < 3; i++) {
      attempts.failed("attacker");
    }
    assertTrue(attempts.lockedFor("attacker").isPresent());
    assertEquals(Optional.empty(), attempts.lockedFor("owner"), "le titulaire n'attend jamais");
    attempts.failed("owner");
    attempts.failed("owner");
    attempts.succeeded("owner");
    attempts.failed("owner");
    attempts.failed("owner");
    assertEquals(Optional.empty(), attempts.lockedFor("owner"), "un PIN juste a effacé l'ardoise");
  }

  @Test
  void aFailureDuringTheLock_neitherExtendsNorShortensIt() {
    for (int i = 0; i < 3; i++) {
      attempts.failed("a");
    }
    clock.advance(Duration.ofSeconds(60));
    attempts.failed("a");
    assertEquals(Optional.of(60L), attempts.lockedFor("a"));
  }
}
