package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Le contrat /evaluate ne porte plus aucune valeur du contact de confiance, ABANDONNÉ
 * (REGISTRE_DECISIONS P-01) : ni preuve TRUSTED_CONTACT, ni action CONTACT_CONFIRMATION.
 */
class EvaluateContractTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schema(String name) throws Exception {
    try (InputStream in = Files.newInputStream(Path.of("../contracts/evaluate.yaml"))) {
      Map<String, Object> doc = new Yaml().load(in);
      Map<String, Object> components = (Map<String, Object>) doc.get("components");
      Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
      return (Map<String, Object>) ((Map<String, Object>) schemas.get(name)).get("properties");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> enumOf(Map<String, Object> properties, String field) {
    return (List<String>) ((Map<String, Object>) properties.get(field)).get("enum");
  }

  @Test
  void proofType_offersNoTrustedContact() throws Exception {
    assertEquals(List.of("SIGNATURE", "NONE"), enumOf(schema("EvaluateRequest"), "proofType"));
  }

  @Test
  void requiredAction_offersNoContactConfirmation() throws Exception {
    assertEquals(
        List.of("SIGNATURE", "DEFER", "NONE"),
        enumOf(schema("EvaluateResponse"), "requiredAction"));
  }
}
