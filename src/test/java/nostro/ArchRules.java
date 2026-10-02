package nostro;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Determinism is enforced here, not hoped for. The core may not read a clock, generate randomness, use an unordered
 * collection, touch floating point, or do I/O. If a future change needs one of these, this test is where the
 * conversation starts.
 */
@AnalyzeClasses(packages = "nostro", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRules {

    @ArchTest
    static final ArchRule core_reads_no_clock = noClasses().that().resideInAPackage("..core..")
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(System.class, "currentTimeMillis")
        .orShould().callMethod(System.class, "nanoTime")
        .orShould().dependOnClassesThat().belongToAnyOf(LocalDate.class, LocalDateTime.class, ZonedDateTime.class);

    @ArchTest
    static final ArchRule core_has_no_randomness = noClasses().that().resideInAPackage("..core..")
        .should().callMethod(Math.class, "random")
        .orShould().callMethod(UUID.class, "randomUUID")
        .orShould().dependOnClassesThat().belongToAnyOf(Random.class, UUID.class);

    @ArchTest
    static final ArchRule core_uses_only_ordered_collections = noClasses().that().resideInAPackage("..core..")
        .should().dependOnClassesThat().belongToAnyOf(HashMap.class, HashSet.class, LinkedHashMap.class, ConcurrentHashMap.class);

    @ArchTest
    static final ArchRule core_has_no_floating_point_fields = fields().that().areDeclaredInClassesThat().resideInAPackage("..core..")
        .should().notHaveRawType(double.class).andShould().notHaveRawType(float.class);

    @ArchTest
    static final ArchRule core_has_no_floating_point_returns = methods().that().areDeclaredInClassesThat().resideInAPackage("..core..")
        .should().notHaveRawReturnType(double.class).andShould().notHaveRawReturnType(float.class);

    @ArchTest
    static final ArchRule core_has_no_boxed_or_decimal_floats = noClasses().that().resideInAPackage("..core..")
        .should().dependOnClassesThat().belongToAnyOf(Double.class, Float.class, java.math.BigDecimal.class);

    @ArchTest
    static final ArchRule core_does_no_io_and_no_threads = noClasses().that().resideInAPackage("..core..")
        .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.file..", "java.net..", "java.sql..", "java.util.concurrent..")
        .orShould().dependOnClassesThat().belongToAnyOf(Thread.class);

    @ArchTest
    static final ArchRule core_depends_on_nothing_outside_itself = noClasses().that().resideInAPackage("..core..")
        .should().dependOnClassesThat().resideInAnyPackage("nostro.store..", "nostro.rail..", "nostro.recon..")
        .orShould().dependOnClassesThat().belongToAnyOf(Engine.class, Simulation.class, Views.class, Main.class);
}
