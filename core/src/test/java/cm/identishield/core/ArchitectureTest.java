package cm.identishield.core;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TA-CLOCK-01 (ENF-11, ISC-SAD C-08): no static system-clock read outside a {@code time} package.
 * Time is injected through a {@code Clock}; overloads taking a {@code Clock} argument (e.g. {@code
 * Instant.now(clock)}) stay allowed everywhere. Scope: every compiled production class of this
 * module, wherever its package lives.
 */
class ArchitectureTest {

  /** All compiled production classes - not a package list (TACHE-01 correction 1). */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter().importPath(Paths.get("build/classes/java/main"));

  private static final Set<String> NO_ARG_NOW_TYPES =
      Set.of(
          "java.time.Instant",
          "java.time.LocalDate",
          "java.time.LocalDateTime",
          "java.time.LocalTime",
          "java.time.ZonedDateTime",
          "java.time.OffsetDateTime",
          "java.time.OffsetTime",
          "java.time.Year",
          "java.time.YearMonth",
          "java.time.MonthDay");

  private static final Set<String> CLOCK_FACTORIES =
      Set.of("systemUTC", "systemDefaultZone", "system");

  private static final DescribedPredicate<JavaMethodCall> STATIC_CLOCK_READ =
      new DescribedPredicate<>("a static system-clock read (TA-CLOCK-01)") {
        @Override
        public boolean test(JavaMethodCall call) {
          String owner = call.getTargetOwner().getFullName();
          String name = call.getName();
          int paramCount = call.getTarget().getRawParameterTypes().size();
          if (NO_ARG_NOW_TYPES.contains(owner) && "now".equals(name) && paramCount == 0) {
            return true;
          }
          if ("java.time.Clock".equals(owner) && CLOCK_FACTORIES.contains(name)) {
            return true;
          }
          if ("java.lang.System".equals(owner)
              && ("currentTimeMillis".equals(name) || "nanoTime".equals(name))) {
            return true;
          }
          return "java.util.Calendar".equals(owner) && "getInstance".equals(name);
        }
      };

  private static final DescribedPredicate<JavaConstructorCall> CLOCK_READING_CONSTRUCTOR =
      new DescribedPredicate<>("a clock-reading no-arg constructor (TA-CLOCK-01)") {
        @Override
        public boolean test(JavaConstructorCall call) {
          String owner = call.getTargetOwner().getFullName();
          return ("java.util.Date".equals(owner) || "java.util.GregorianCalendar".equals(owner))
              && call.getTarget().getRawParameterTypes().isEmpty();
        }
      };

  @Test
  void taClock01_noStaticClockReadOutsideTimePackage() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideOutsideOfPackage("..time..")
            .should()
            .callMethodWhere(STATIC_CLOCK_READ)
            .orShould()
            .callConstructorWhere(CLOCK_READING_CONSTRUCTOR)
            .because("TA-CLOCK-01: time must come from the injected Clock (ISC-SAD C-08)");
    rule.check(PRODUCTION_CLASSES);
  }
}
