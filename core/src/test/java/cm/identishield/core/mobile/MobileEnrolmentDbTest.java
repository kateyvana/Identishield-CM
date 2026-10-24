package cm.identishield.core.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.core.account.AccountRepository;
import cm.identishield.core.config.RuleSet;
import cm.identishield.core.hlr.HlrRepository;
import cm.identishield.core.hlr.HlrService;
import cm.identishield.core.mobile.DeviceClient.Reply;
import cm.identishield.core.mobile.MobileEnrolment.Answer;
import cm.identishield.core.otp.OtpRepository;
import cm.identishield.core.otp.OtpService;
import cm.identishield.core.otp.SmsGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Premier enrôlement côté cœur sur une VRAIE base (init.sql, rôle isc_core_app) : les GRANT de la
 * section B3a couvrent enrôlement, session et colonnes du PIN ; le CHECK de l'étape refuse tout
 * autre état ; la consommation n'est obtenue qu'une fois ; le parcours serveur start → otp →
 * complete → confirm aboutit, avec un PDP simulé. S'arrêter après complete ne laisse AUCUNE session
 * (la clé EN ATTENTE ne signe rien : prouvé côté PDP par DeviceEnrolmentDbTest).
 *
 * <p>Sans base (ISC_TEST_PG_URL absente), la classe est ignorée ; tools/verify_device_enrolment.sh
 * la lance contre une base jetable dans la VM.
 *
 * <p>@covers ES-25
 *
 * <p>@covers ES-26
 */
@EnabledIfEnvironmentVariable(named = "ISC_TEST_PG_URL", matches = ".+")
class MobileEnrolmentDbTest {

  private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
  private static final String PIN = "4096";
  private static final String DEVICE = "6f0a2c1e-0000-4000-8000-000000000001";

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static RuleSet ruleSet;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final PinHasher hasher = new PinHasher(new PinProperties(1_000, 16, 256));

  @BeforeAll
  static void connect() throws Exception {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            System.getenv("ISC_TEST_PG_URL"),
            System.getenv("ISC_TEST_PG_USER"),
            System.getenv("ISC_TEST_PG_PASSWORD"));
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    ruleSet =
        new Binder(
                ConfigurationPropertySources.from(
                    new YamlPropertySourceLoader()
                        .load("ruleset", new FileSystemResource("../config/ruleset.yaml"))))
            .bind("ruleset", RuleSet.class)
            .get();
  }

  /** Compte de la plage fictive, avec PIN et abonné HLR. */
  private UUID account(String msisdn) {
    new AccountRepository(jdbc).insertIfAbsent(msisdn, 0, NOW);
    new PinStore(jdbc).setIfAbsent(msisdn, hasher.protect(PIN));
    hlr().ensureSubscriber(msisdn, imsi(msisdn));
    return new PinStore(jdbc).find(msisdn).orElseThrow().accountId();
  }

  /** IMSI de structure MTN (624-01) propre au test : l'amorçage de démo n'est pas requis ici. */
  private static String imsi(String msisdn) {
    return "624010" + msisdn;
  }

  private HlrService hlr() {
    return new HlrService(new HlrRepository(jdbc), clock, mock(ApplicationEventPublisher.class));
  }

  @Test
  void theApplicationRole_storesOnlyThePinHash_andFindsItBack() {
    account("677009101");
    PinStore pins = new PinStore(jdbc);
    assertTrue(hasher.matches(PIN, pins.find("677009101").orElseThrow().pin()));
    assertFalse(pins.setIfAbsent("677009101", hasher.protect("1111")), "un PIN posé reste");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM core.account WHERE msisdn = ? AND pin_hash LIKE ?",
            Integer.class,
            "677009101",
            "%" + PIN + "%"));
  }

  @Test
  void theStageCheck_refusesAnyOtherState_andConsumptionHappensOnce() {
    UUID acc = account("677009102");
    Enrolments enrolments = new Enrolments(jdbc);
    UUID id = enrolments.open(acc, NOW, NOW.plusSeconds(600));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE core.enrolment SET stage = 'BOGUS' WHERE id = ?", id));
    assertTrue(enrolments.toBind(id, "Y2hhbGxlbmdl"));
    assertFalse(enrolments.toBind(id, "Y2hhbGxlbmdl"), "une transition ne se fait qu'une fois");
    assertTrue(enrolments.toConfirm(id, DEVICE));
    assertTrue(enrolments.consume(id, "CONFIRM"));
    assertFalse(enrolments.consume(id, "CONFIRM"), "consommé une seule fois");
    assertEquals("DONE", enrolments.find(id).orElseThrow().stage());
  }

  @Test
  void serverSideFirstEnrolment_startOtpCompleteConfirm() {
    String msisdn = "677009103";
    UUID acc = account(msisdn);
    DeviceClient device = mock(DeviceClient.class);
    SmsGateway sms = mock(SmsGateway.class);
    when(device.state(acc.toString())).thenReturn(new Reply(200, Map.of("state", "NONE")));
    when(device.register(
            eq(acc.toString()),
            anyString(),
            any(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            any()))
        .thenReturn(new Reply(200, Map.of("deviceId", DEVICE, "recoveryCode", "ABCDEF")));
    when(device.activate(acc.toString(), DEVICE, "ABCDEF"))
        .thenReturn(new Reply(200, Map.of("deviceId", DEVICE)));
    SessionTokens sessions =
        new SessionTokens(
            jdbc,
            clock,
            ruleSet.appSessionTtl(),
            ruleSet.appSessionBackgroundTtl(),
            ruleSet.appSessionMaxTtl());
    PinAttempts attempts =
        new PinAttempts(clock, ruleSet.appPinMaxAttempts(), ruleSet.appPinOriginLock());
    MobileEnrolment service =
        new MobileEnrolment(
            new PinStore(jdbc),
            hasher,
            attempts,
            device,
            new Enrolments(jdbc),
            new OtpService(new OtpRepository(jdbc), hlr(), ruleSet, clock),
            sms,
            sessions,
            new MobileSession(new PinStore(jdbc), hasher, attempts, device, sessions),
            ruleSet,
            clock,
            tx);

    Answer start = service.start(msisdn, PIN, "100.64.0.9", null, null, null);
    assertEquals("OTP", start.body().get("next"));
    UUID id = UUID.fromString((String) start.body().get("enrolmentId"));
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(sms).deliver(eq(imsi(msisdn)), code.capture());

    assertEquals("BIND", service.otp(id, code.getValue()).body().get("next"));
    Answer complete =
        service.complete(id, "cGs=", List.of("Y2VydA=="), "c2ln", null, "100.64.0.9", null);
    assertEquals("ABCDEF", complete.body().get("recoveryCode"));
    assertEquals(0, sessionsOf(acc), "arrêt après complete : aucune session, rien d'utilisable");

    Answer confirm = service.confirm(id, "ABCDEF");
    String token = (String) confirm.body().get("sessionToken");
    assertEquals(1, sessionsOf(acc));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM core.session WHERE token_hash = ?",
            Integer.class,
            SessionTokens.sha256Hex(token)));
    assertEquals(410, service.confirm(id, "ABCDEF").status(), "enrôlement consommé");
  }

  private int sessionsOf(UUID acc) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM core.session WHERE account_id = ?", Integer.class, acc);
  }
}
