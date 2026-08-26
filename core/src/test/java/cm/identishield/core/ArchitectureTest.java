package cm.identishield.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * TA-CLOCK-01 (ENF-11, ISC-SAD C-08): no static clock read outside a {@code time} package. Time is
 * injected through a {@code Clock} interface; the overloads taking a {@code Clock} argument remain
 * allowed everywhere.
 */
class ArchitectureTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("cm.identishield.core");

  @Test
  void taClock01_noStaticClockReadOutsideTimePackage() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideOutsideOfPackage("..time..")
            .should()
            .callMethod(Instant.class, "now")
            .orShould()
            .callMethod(LocalDateTime.class, "now")
            .orShould()
            .callMethod(LocalDate.class, "now")
            .orShould()
            .callMethod(System.class, "currentTimeMillis")
            .orShould()
            .callConstructor(Date.class)
            .because("TA-CLOCK-01: time must come from the injected Clock (ISC-SAD C-08)");
    rule.check(PRODUCTION_CLASSES);
  }
}
