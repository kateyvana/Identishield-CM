package cm.identishield.pdp.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.config.RuleSet;
import cm.identishield.pdp.config.TestRuleSet;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Code de récupération (ADR-041 §5) : produit par le PDP, CODE_LENGTH caractères d'un alphabet sans
 * 0/O/1/I/L, renvoyé une fois ; seule une empreinte PBKDF2 à sel unique est conservée.
 *
 * <p>@covers ES-18
 */
class RecoveryCodesTest {

  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");

  /** Itérations réduites pour la vitesse des tests ; la production lit sa configuration. */
  private static final RecoveryCodeProperties PROPS = new RecoveryCodeProperties(1_000, 16, 256);

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final RuleSet rules = TestRuleSet.load();
  private final RecoveryCodes codes = new RecoveryCodes(jdbc, rules, PROPS);

  /** Ligne enregistrée par issue() : code_hash, salt, iterations. */
  private Object[] storedRow() {
    ArgumentCaptor<Object> hash = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> salt = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<Object> iterations = ArgumentCaptor.forClass(Object.class);
    verify(jdbc)
        .update(
            eq(RecoveryCodes.INSERT_SQL),
            eq("dev-1"),
            hash.capture(),
            salt.capture(),
            iterations.capture(),
            any());
    return new Object[] {hash.getValue(), salt.getValue(), iterations.getValue()};
  }

  @Test
  void issuedCode_hasCodeLength_fromTheAlphabetWithout0O1IL() {
    String code = codes.issue("dev-1", T0);
    assertEquals(rules.integer("CODE_LENGTH"), code.length());
    for (char c : code.toCharArray()) {
      assertTrue(RecoveryCodes.ALPHABET.indexOf(c) >= 0, "caractère hors alphabet : " + c);
    }
    assertTrue(RecoveryCodes.ALPHABET.chars().noneMatch(c -> "0O1IL".indexOf(c) >= 0));
  }

  @Test
  void onlyASaltedHash_isStored_neverTheCode() {
    String code = codes.issue("dev-1", T0);
    Object[] row = storedRow();
    assertNotEquals(code, row[0]);
    assertFalse(row[0].toString().contains(code));
    assertEquals(PROPS.saltBytes(), Base64.getDecoder().decode(row[1].toString()).length);
    assertEquals(PROPS.iterations(), row[2]);
  }

  @Test
  void twoCodes_neverShareASalt() {
    codes.issue("dev-1", T0);
    codes.issue("dev-2", T0);
    ArgumentCaptor<Object> salt = ArgumentCaptor.forClass(Object.class);
    verify(jdbc, org.mockito.Mockito.times(2))
        .update(eq(RecoveryCodes.INSERT_SQL), anyString(), any(), salt.capture(), any(), any());
    assertNotEquals(salt.getAllValues().get(0), salt.getAllValues().get(1));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theIssuedCode_matches_anotherDoesNot_andAnUnknownDeviceNeverMatches() {
    String code = codes.issue("dev-1", T0);
    Object[] row = storedRow();
    Map<String, Object> stored = Map.of("code_hash", row[0], "salt", row[1], "iterations", row[2]);
    when(jdbc.query(eq(RecoveryCodes.FIND_SQL), any(RowMapper.class), eq("dev-1")))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
              when(rs.getString("code_hash")).thenReturn((String) stored.get("code_hash"));
              when(rs.getString("salt")).thenReturn((String) stored.get("salt"));
              when(rs.getInt("iterations")).thenReturn((Integer) stored.get("iterations"));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(eq(RecoveryCodes.FIND_SQL), any(RowMapper.class), eq("dev-unknown")))
        .thenReturn(List.of());

    assertTrue(codes.matches("dev-1", code));
    String oneCharOff = (code.charAt(0) == 'A' ? "B" : "A") + code.substring(1);
    assertFalse(codes.matches("dev-1", oneCharOff));
    assertFalse(codes.matches("dev-unknown", code));
  }
}
