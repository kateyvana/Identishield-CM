package cm.identishield.pdp.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.config.RuleSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Nonces : 128 bits liés au compte, durée NONCE_TTL de la configuration, consommation par UPDATE
 * conditionnel. L'atomicité elle-même est celle de PostgreSQL (preuve en base réelle : VM).
 *
 * <p>@covers ES-10
 */
class NonceStoreTest {

  private static final Instant T0 = Instant.parse("2026-10-06T09:14:00Z");
  private static final OffsetDateTime T0_UTC = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RuleSet ruleSet = new RuleSet("rs-test", Map.of("NONCE_TTL", "PT120S"));
  private final NonceStore store = new NonceStore(jdbc, Clock.fixed(T0, ZoneOffset.UTC), ruleSet);

  @Test
  void issue_128BitHex_boundToTheAccount_expiresAfterTheConfiguredTtl() {
    NonceStore.IssuedNonce a = store.issue("acc-000123");
    NonceStore.IssuedNonce b = store.issue("acc-000123");
    assertTrue(a.value().matches("^[0-9a-f]{32}$"));
    assertNotEquals(a.value(), b.value());
    assertEquals(T0, a.issuedAt());
    assertEquals(T0.plus(Duration.ofSeconds(120)), a.expiresAt());
    verify(jdbc)
        .update(
            anyString(), eq(a.value()), eq("acc-000123"), eq(T0_UTC), eq(T0_UTC.plusSeconds(120)));
  }

  @Test
  void consume_isOneConditionalUpdate_trueOnlyIfThisCallConsumedIt() {
    String n = "0123456789abcdef0123456789abcdef";
    when(jdbc.update(NonceStore.CONSUME_SQL, T0_UTC, n, "acc-000123", T0_UTC)).thenReturn(1, 0);
    assertTrue(store.consume(n, "acc-000123"));
    assertFalse(store.consume(n, "acc-000123"), "seconde présentation : aucune ligne consommée");
    // La condition d'usage unique, de compte et d'expiration est dans la MÊME instruction.
    assertTrue(NonceStore.CONSUME_SQL.contains("consumed_at IS NULL"));
    assertTrue(NonceStore.CONSUME_SQL.contains("account_id = ?"));
    assertTrue(NonceStore.CONSUME_SQL.contains("expires_at > ?"));
  }
}
