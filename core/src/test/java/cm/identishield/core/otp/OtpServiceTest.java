package cm.identishield.core.otp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OTP à usage unique (EF-03) : émis vers l'IMSI courant, empreinte stockée, expiration depuis
 * l'horloge injectée, usage unique et motifs de refus distincts.
 *
 * @covers EF-03
 */
class OtpServiceTest {

  private static final String MSISDN = "677000001";
  private static final String IMSI = "624010000000001";
  private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

  private final OtpRepository repo = mock(OtpRepository.class);
  private final HlrService hlr = mock(HlrService.class);
  private final RuleSet ruleSet =
      new RuleSet("rs-test", Map.of("OTP", Map.of("length", 6, "ttl", "PT5M")));
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OtpService service = new OtpService(repo, hlr, ruleSet, clock);

  @Test
  void issueTargetsCurrentImsiAndSetsTtlFromInjectedClock() {
    when(hlr.currentImsi(MSISDN)).thenReturn(Optional.of(IMSI));

    OtpEmission em = service.issue(MSISDN);

    assertEquals(IMSI, em.targetImsi());
    assertTrue(em.code().matches("\\d{6}"), "6 chiffres décimaux");
    ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(repo).issue(eq(MSISDN), eq(IMSI), hash.capture(), expiresAt.capture());
    assertEquals(NOW.plus(Duration.ofMinutes(5)), expiresAt.getValue()); // now + OTP.ttl
    assertTrue(hash.getValue().matches("[0-9a-f]{64}"), "empreinte SHA-256, jamais le clair");
  }

  @Test
  void issueThrowsWhenNumberHasNoImsi() {
    when(hlr.currentImsi(MSISDN)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> service.issue(MSISDN));
  }

  @Test
  void verifyAcceptsAndConsumesAValidCode() {
    when(repo.consumeIfValid(eq(MSISDN), anyString(), eq(NOW))).thenReturn(1);
    assertEquals(OtpOutcome.ACCEPTED, service.verify(MSISDN, "123456"));
  }

  @Test
  void verifyRefusesAConsumedCodeAsReuse() {
    when(repo.consumeIfValid(any(), anyString(), any())).thenReturn(0);
    when(repo.reasonFor(any(), anyString(), any())).thenReturn(OtpOutcome.REFUSED_CONSUMED);
    assertEquals(OtpOutcome.REFUSED_CONSUMED, service.verify(MSISDN, "123456"));
  }

  @Test
  void verifyRefusesAnExpiredCode() {
    when(repo.consumeIfValid(any(), anyString(), any())).thenReturn(0);
    when(repo.reasonFor(any(), anyString(), any())).thenReturn(OtpOutcome.REFUSED_EXPIRED);
    assertEquals(OtpOutcome.REFUSED_EXPIRED, service.verify(MSISDN, "123456"));
  }

  @Test
  void verifyRefusesAnUnknownCode() {
    when(repo.consumeIfValid(any(), anyString(), any())).thenReturn(0);
    when(repo.reasonFor(any(), anyString(), any())).thenReturn(OtpOutcome.REFUSED_UNKNOWN);
    assertEquals(OtpOutcome.REFUSED_UNKNOWN, service.verify(MSISDN, "000000"));
  }
}
