package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.device.EnrolmentThrottle.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-030 : délai LOCK_BASE doublé à chaque échec au-delà de REENROL_ATTEMPTS, plafonné par
 * LOCK_MAX ; jamais de débordement. Le comptage en base est prouvé par DeviceEnrolmentDbTest.
 *
 * <p>@covers ES-21
 */
class EnrolmentThrottleTest {

  private static final Duration BASE = Duration.ofSeconds(30);
  private static final Duration MAX = Duration.ofHours(24);

  // ---- lockedFor / failed / succeeded sur un JdbcTemplate simulé (B3b, clôture de #62) ----------
  // La mutation « lockedFor rend toujours vide » survivait aux tests unitaires et n'était tuée que
  // sur vraie base : ces tests la tuent sans base, mêmes règles (ADR-030, ES-21).

  private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RuleSet rules =
      new RuleSet(
          "rs-test", Map.of("REENROL_ATTEMPTS", 2, "LOCK_BASE", "PT30S", "LOCK_MAX", "PT24H"));
  private final EnrolmentThrottle throttle =
      new EnrolmentThrottle(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), rules);

  private static OffsetDateTime at(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private void lockedUntil(Key k, Instant... untils) {
    when(jdbc.queryForList(EnrolmentThrottle.LOCKED_SQL, OffsetDateTime.class, k.kind(), k.value()))
        .thenReturn(java.util.Arrays.stream(untils).map(EnrolmentThrottleTest::at).toList());
  }

  @Test
  void lockedFor_isTheRemainingSecondsOfTheWorstKey_roundedUp_emptyWhenNothingHolds() {
    Key origin = Key.origin("100.64.0.9");
    Key install = Key.installation("6f0a2c1e-0000-4000-8000-000000000001");
    lockedUntil(origin, NOW.plusSeconds(29).plusMillis(1));
    lockedUntil(install, NOW.plusSeconds(120));
    assertEquals(Optional.of(120L), throttle.lockedFor(List.of(origin, install)), "la pire clé");
    assertEquals(Optional.of(30L), throttle.lockedFor(List.of(origin)), "arrondi au-dessus");

    lockedUntil(origin, NOW.minusSeconds(1)); // verrou expiré
    lockedUntil(install); // jamais verrouillée (aucune ligne)
    assertEquals(Optional.empty(), throttle.lockedFor(List.of(origin, install)));
    assertEquals(Optional.empty(), throttle.lockedFor(List.of()));
  }

  @Test
  void failed_locksOriginAndInstallationFromTheNthFailure_neverTheAccount() {
    Key origin = Key.origin("100.64.0.9");
    Key account = Key.account("acc-1");
    when(jdbc.queryForObject(
            eq(EnrolmentThrottle.FAIL_SQL),
            eq(Integer.class),
            eq("ORIGIN"),
            eq(origin.value()),
            any()))
        .thenReturn(1, 2, 3);
    when(jdbc.queryForObject(
            eq(EnrolmentThrottle.FAIL_SQL), eq(Integer.class), eq("ACCOUNT"), eq("acc-1"), any()))
        .thenReturn(5);

    throttle.failed(List.of(origin, account)); // 1er échec : rien n'est verrouillé
    verify(jdbc, never()).update(eq(EnrolmentThrottle.LOCK_SQL), any(), any(), any());

    throttle.failed(List.of(origin, account)); // 2e = REENROL_ATTEMPTS : LOCK_BASE sur l'origine
    verify(jdbc)
        .update(EnrolmentThrottle.LOCK_SQL, at(NOW.plusSeconds(30)), "ORIGIN", origin.value());

    throttle.failed(List.of(origin, account)); // 3e : doublé
    verify(jdbc)
        .update(EnrolmentThrottle.LOCK_SQL, at(NOW.plusSeconds(60)), "ORIGIN", origin.value());

    // Le compte est compté (FAIL_SQL ×3) mais JAMAIS verrouillé, malgré 5 échecs (ES-21).
    verify(jdbc, times(3))
        .queryForObject(
            eq(EnrolmentThrottle.FAIL_SQL), eq(Integer.class), eq("ACCOUNT"), eq("acc-1"), any());
    verify(jdbc, never()).update(eq(EnrolmentThrottle.LOCK_SQL), any(), eq("ACCOUNT"), any());
  }

  @Test
  void succeeded_clearsEveryKeyOfTheCaller() {
    Key origin = Key.origin("100.64.0.9");
    Key account = Key.account("acc-1");
    throttle.succeeded(List.of(origin, account));
    verify(jdbc).update(EnrolmentThrottle.CLEAR_SQL, "ORIGIN", origin.value());
    verify(jdbc).update(EnrolmentThrottle.CLEAR_SQL, "ACCOUNT", "acc-1");
  }

  @Test
  void theDelayDoublesFromTheNthFailure_andIsCapped() {
    assertEquals(BASE, EnrolmentThrottle.lockAfter(2, 2, BASE, MAX), "au N-ième : LOCK_BASE");
    assertEquals(BASE.multipliedBy(2), EnrolmentThrottle.lockAfter(3, 2, BASE, MAX));
    assertEquals(BASE.multipliedBy(4), EnrolmentThrottle.lockAfter(4, 2, BASE, MAX));
    assertEquals(MAX, EnrolmentThrottle.lockAfter(20, 2, BASE, MAX), "plafonné");
    assertEquals(
        MAX, EnrolmentThrottle.lockAfter(2_000_000, 2, BASE, MAX), "jamais de débordement");
  }
}
