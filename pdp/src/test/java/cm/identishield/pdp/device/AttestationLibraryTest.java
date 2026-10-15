package cm.identishield.pdp.device;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ADR-040 : l'extension d'attestation (KeyDescription) est lue par BouncyCastle ; le lecteur DER
 * maison (Der.java) a disparu du code de production.
 */
class AttestationLibraryTest {

  private final JavaClasses production =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("cm.identishield.pdp.device");

  @Test
  void theHomemadeDerReader_isGone() {
    assertTrue(
        production.stream().noneMatch(c -> c.getSimpleName().startsWith("Der")),
        "Der.java doit être retiré (ADR-040)");
  }

  @Test
  void keyDescription_isReadWithBouncyCastle() {
    classes()
        .that()
        .haveSimpleName("AttestationVerifier")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.bouncycastle.asn1..")
        .check(production);
  }
}
