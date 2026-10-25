package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.yaml.snakeyaml.Yaml;

/**
 * Prêt par construction du chemin d'enrôlement (D-B7-1, décision du 2026-10-25) : à sa
 * construction, {@link EnrolmentReadiness} parcourt l'attestation, une dérivation PBKDF2 aux
 * itérations de production et une vérification ECDSA, sans aucune écriture en base.
 *
 * <p>@covers ES-11 ; @covers ES-18
 */
class EnrolmentReadinessTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @SuppressWarnings("unchecked")
  private static RecoveryCodeProperties productionPbkdf2() throws Exception {
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
      Map<String, Object> root = new Yaml().load(in);
      Map<String, Object> p =
          (Map<String, Object>)
              ((Map<String, Object>) ((Map<String, Object>) root.get("isc")).get("recovery-code"))
                  .get("pbkdf2");
      return new RecoveryCodeProperties(
          (Integer) p.get("iterations"),
          (Integer) p.get("salt-bytes"),
          (Integer) p.get("key-bits"));
    }
  }

  @SuppressWarnings("unchecked")
  private EnrolmentReadiness build(RecoveryCodes codes) {
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    return new EnrolmentReadiness(new AttestationVerifier(), codes, new ObjectMapper());
  }

  @Test
  void theVersionedSample_runsTheWholeAttestationPath_andItsSignatureVerifies() throws Exception {
    RuleSet rules = TestRuleSet.load();
    EnrolmentReadiness r = build(new RecoveryCodes(jdbc, rules, productionPbkdf2()));
    assertEquals(AttestationVerifier.Outcome.VALID, r.warmed().attestation());
    assertTrue(r.warmed().signatureValid());
    assertTrue(r.warmed().pbkdf2Ran());
  }

  @Test
  void thePbkdf2Derivation_goesThroughTheProductionPath_onAnAbsentDevice() {
    RecoveryCodes codes = mock(RecoveryCodes.class);
    build(codes);
    // Un appareil qui n'existe pas et un code jeté : le même travail qu'un code faux.
    verify(codes, times(1)).matches(anyString(), anyString());
    verify(codes, never()).issue(anyString(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void initialisation_neverWritesToTheDatabase() throws Exception {
    build(new RecoveryCodes(jdbc, TestRuleSet.load(), productionPbkdf2()));
    // Une lecture (l'empreinte de l'appareil absent), jamais d'écriture.
    verify(jdbc, times(1)).query(eq(RecoveryCodes.FIND_SQL), any(RowMapper.class), any());
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void theSample_holdsNoPrivateKey() throws Exception {
    String sample =
        new String(
            EnrolmentReadiness.class
                .getClassLoader()
                .getResourceAsStream(EnrolmentReadiness.SAMPLE)
                .readAllBytes());
    assertFalse(sample.contains("PRIVATE"), "aucune clé privée dans la ressource");
    assertFalse(sample.contains("privateKey"), "aucune clé privée dans la ressource");
  }
}
