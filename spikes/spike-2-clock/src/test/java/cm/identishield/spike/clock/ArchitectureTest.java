package cm.identishield.spike.clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.identishield.spike.clock.contournement.EntropieUuid;
import cm.identishield.spike.clock.contournement.GraineTemporelle;
import cm.identishield.spike.clock.rules.Rule;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Step 4 (spec §4/§7, Q6, Q7). TA-CLOCK-01 is copied verbatim from the product's hardened version
 * (TACHE-01), applied to the spike's own production code, then confronted with six deliberate
 * bypass attempts compiled in the contournement source set. The hardened variant below is a
 * PROPOSAL measured here; the product rule is not modified by this spike.
 */
class ArchitectureTest {

  private static final JavaClasses SPIKE_CLASSES =
      new ClassFileImporter().importPath(Paths.get("build/classes/java/main"));

  private static final JavaClasses CONTOURNEMENT_CLASSES =
      new ClassFileImporter().importPath(Paths.get("build/classes/java/contournement"));

  // ---- TA-CLOCK-01, copie conforme de la version durcie du produit ----

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

  private static final ArchRule TA_CLOCK_01 =
      ArchRuleDefinition.noClasses()
          .that()
          .resideOutsideOfPackage("..time..")
          .should()
          .callMethodWhere(STATIC_CLOCK_READ)
          .orShould()
          .callConstructorWhere(CLOCK_READING_CONSTRUCTOR)
          .because("TA-CLOCK-01: time must come from the injected Clock (ISC-SAD C-08)");

  // ---- Durcissement PROPOSÉ (non appliqué au produit) ----

  private static final DescribedPredicate<JavaMethodCall> HARDENED_METHOD_READ =
      new DescribedPredicate<>("an indirect clock or entropy read (TA-CLOCK-01-DURCI proposal)") {
        @Override
        public boolean test(JavaMethodCall call) {
          if (STATIC_CLOCK_READ.test(call)) {
            return true;
          }
          String owner = call.getTargetOwner().getFullName();
          if ("java.util.UUID".equals(owner) && "randomUUID".equals(call.getName())) {
            return true;
          }
          // Known third-party clock wrappers of the approved stack, deny-listed by package.
          if (owner.startsWith("org.apache.commons.lang3.time.")) {
            return true;
          }
          // No timestamped logging from inside a rule.
          return "java.util.logging.Logger".equals(owner);
        }
      };

  private static final DescribedPredicate<JavaConstructorCall> HARDENED_CONSTRUCTOR =
      new DescribedPredicate<>("a time-seeded no-arg constructor (TA-CLOCK-01-DURCI proposal)") {
        @Override
        public boolean test(JavaConstructorCall call) {
          if (CLOCK_READING_CONSTRUCTOR.test(call)) {
            return true;
          }
          // Q7 core case: new Random() seeds itself on the system clock inside the JDK.
          return "java.util.Random".equals(call.getTargetOwner().getFullName())
              && call.getTarget().getRawParameterTypes().isEmpty();
        }
      };

  private static final ArchRule TA_CLOCK_01_DURCI =
      ArchRuleDefinition.noClasses()
          .that()
          .resideOutsideOfPackage("..time..")
          .should()
          .callMethodWhere(HARDENED_METHOD_READ)
          .orShould()
          .callConstructorWhere(HARDENED_CONSTRUCTOR)
          .because("TA-CLOCK-01-DURCI proposal: closes Random(), randomUUID, wrappers, logging");

  private static String report(ArchRule rule, JavaClasses classes) {
    return String.join("\n", rule.evaluate(classes).getFailureReport().getDetails());
  }

  // ---- Q6 : la règle actuelle est verte sur le code du spike ----

  @Test
  void taClock01_verteSurLeCodeDuSpike() {
    TA_CLOCK_01.check(SPIKE_CLASSES);
  }

  @Test
  void durcissementPropose_resteVertSurLeCodeDuSpike() {
    TA_CLOCK_01_DURCI.check(SPIKE_CLASSES);
  }

  // ---- Q7 : lectures directes détectées ----

  @Test
  void taClock01_detecteLesLecturesDirectes() {
    String details = report(TA_CLOCK_01, CONTOURNEMENT_CLASSES);
    System.out.println("== violations TA-CLOCK-01 (contournement) ==");
    System.out.println(details);
    assertTrue(details.contains("LectureDirecte"), "Instant.now() doit etre detecte");
    assertTrue(details.contains("LectureSysteme"), "System.currentTimeMillis() doit etre detecte");
  }

  // ---- Q7 : angles morts CONSTATES de la règle actuelle ----

  @Test
  void taClock01_angleMorts_constates() {
    String details = report(TA_CLOCK_01, CONTOURNEMENT_CLASSES);
    assertFalse(details.contains("GraineTemporelle"), "new Random() n'est PAS vu (constat)");
    assertFalse(details.contains("BibliothequeTierce"), "wrapper tiers PAS vu (constat)");
    assertFalse(details.contains("JournalHorodate"), "journalisation PAS vue (constat)");
    assertFalse(details.contains("EntropieUuid"), "randomUUID PAS vu (constat)");
  }

  // ---- Q7 : le durcissement proposé ferme les quatre angles morts ----

  @Test
  void durcissementPropose_fermeLesAngleMorts() {
    String details = report(TA_CLOCK_01_DURCI, CONTOURNEMENT_CLASSES);
    System.out.println("== violations TA-CLOCK-01-DURCI (contournement) ==");
    System.out.println(details);
    for (String cls :
        List.of(
            "LectureDirecte",
            "LectureSysteme",
            "GraineTemporelle",
            "BibliothequeTierce",
            "JournalHorodate",
            "EntropieUuid")) {
      assertTrue(details.contains(cls), cls + " doit etre detecte par la version durcie");
    }
  }

  // ---- Q7 : démonstration d'exécution du non-déterminisme ----

  @Test
  void graineTemporelle_nonDeterministeMalgreInstantPropre() {
    Rule rule = new GraineTemporelle();
    RuleContext ctx = new RuleContext(List.of());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String first = rule.verdict(ctx, now);
    String second = rule.verdict(ctx, now);
    System.out.println("GraineTemporelle run1=" + first);
    System.out.println("GraineTemporelle run2=" + second);
    assertNotEquals(first, second, "meme (historique, instant), verdicts differents");
  }

  @Test
  void entropieUuid_nonDeterministe_etSourceEntropieMesuree() {
    Rule rule = new EntropieUuid();
    RuleContext ctx = new RuleContext(List.of());
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String first = rule.verdict(ctx, now);
    String second = rule.verdict(ctx, now);
    System.out.println("EntropieUuid run1=" + first);
    System.out.println("EntropieUuid run2=" + second);
    System.out.println("SecureRandom.defaultAlgorithm=" + new SecureRandom().getAlgorithm());
    assertNotEquals(first, second, "meme (historique, instant), verdicts differents");
  }
}
