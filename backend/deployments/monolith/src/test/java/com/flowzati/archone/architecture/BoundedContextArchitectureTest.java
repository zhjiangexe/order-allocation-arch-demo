package com.flowzati.archone.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.architecture.BoundedContextArchitectureRules.SharedRule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Applies the shared bounded context rules to every context deployed in the monolith.
 *
 * <p>Contexts are discovered, not listed: any top-level package under {@code com.flowzati.archone} that contains an
 * {@code application} package is a bounded context. A new context is therefore checked automatically, even before it
 * has its own {@code *SharedBoundaryArchitectureTest}. Rule identifiers
 * and fixes are documented in {@code backend/archone-framework/architecture-rules/README.md}.
 */
@DisplayName("Bounded context shared architecture rules")
class BoundedContextArchitectureTest {

    private static final String PROJECT_PACKAGE = "com.flowzati.archone";
    private static final Pattern CONTEXT_WITH_APPLICATION_LAYER =
            Pattern.compile("^" + Pattern.quote(PROJECT_PACKAGE) + "\\.([a-z]+)\\.(?:.+\\.)?application(?:\\..+)?$");

    /** Minimum set that discovery must find; guards against discovery silently matching nothing. */
    private static final Set<String> KNOWN_CONTEXTS =
            Set.of("fulfillment", "inventory", "logisticsdata", "ordering", "wms");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("test-fixtures") && !location.contains("testFixtures"))
            .importPackages(PROJECT_PACKAGE);

    @Test
    @DisplayName("自動找出的 context 至少包含所有已知 context")
    void discoversEveryKnownContext() {
        assertThat(contexts()).containsAll(KNOWN_CONTEXTS);
    }

    @TestFactory
    Stream<DynamicContainer> everyContextFollowsSharedRules() {
        return contexts().stream().map(context -> {
            String basePackage = PROJECT_PACKAGE + "." + context;
            JavaClasses contextClasses =
                    PRODUCTION_CLASSES.that(JavaClass.Predicates.resideInAPackage(basePackage + ".."));
            return DynamicContainer.dynamicContainer(
                    context,
                    BoundedContextArchitectureRules.forContext(basePackage).stream()
                            .map(shared -> DynamicTest.dynamicTest(
                                    shared.rule().getDescription(), () -> check(basePackage, shared, contextClasses))));
        });
    }

    private static void check(String basePackage, SharedRule shared, JavaClasses contextClasses) {
        // 延後處理的規則由該 context 自己的 *SharedBoundaryArchitectureTest 以 freeze 檢查，凍結檔只存一份。
        Assumptions.assumeFalse(
                BoundedContextArchitectureRules.isDeferred(basePackage, shared.id()),
                () -> shared.id() + " 已延後處理，由該 context 的 *SharedBoundaryArchitectureTest 以 freeze 檢查");
        shared.rule().check(contextClasses);
    }

    private static SortedSet<String> contexts() {
        SortedSet<String> contexts = new TreeSet<>();
        for (JavaClass javaClass : PRODUCTION_CLASSES) {
            Matcher matcher = CONTEXT_WITH_APPLICATION_LAYER.matcher(javaClass.getPackageName());
            if (matcher.matches()) {
                contexts.add(matcher.group(1));
            }
        }
        return contexts;
    }
}
