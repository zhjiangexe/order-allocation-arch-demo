package com.flowzati.archone.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@DisplayName("Shared bounded context architecture rules")
class BoundedContextArchitectureRulesTest {

    private static final String FIXTURE = "com.flowzati.archone.architecture.fixture";
    private static final String VIOLATION = FIXTURE + ".violation.";

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages(FIXTURE, "com.flowzati.archone.contracts.fixture");

    @TestFactory
    @DisplayName("Module-first compliant context 通過全部規則")
    Stream<DynamicTest> compliantContextPassesEveryRule() {
        return BoundedContextArchitectureRules.forContext(FIXTURE + ".compliant").stream()
                .map(shared -> DynamicTest.dynamicTest(
                        shared.rule().getDescription(), () -> shared.rule().check(FIXTURE_CLASSES)));
    }

    @TestFactory
    @DisplayName("Single-module compliant context（layer 直接在 base package 下）通過全部規則")
    Stream<DynamicTest> singleModuleCompliantContextPassesEveryRule() {
        return BoundedContextArchitectureRules.forContext(FIXTURE + ".compliant.sales").stream()
                .map(shared -> DynamicTest.dynamicTest(
                        shared.rule().getDescription(), () -> shared.rule().check(FIXTURE_CLASSES)));
    }

    @Test
    @DisplayName("Single-module context 內的 domain 循環也會被擋下")
    void singleModuleDomainCycleIsRejected() {
        ArchRule rule = new BoundedContextArchitectureRules(VIOLATION + "domaincycle.shipping")
                .domainSubpackagesOfEachModuleAreFreeOfCycles();

        assertThat(rule.evaluate(FIXTURE_CLASSES).hasViolation()).isTrue();
    }

    @Test
    @DisplayName("每條規則都有唯一編號，且說明以編號開頭，方便對照 README")
    void everyRuleHasUniqueIdentifierInItsDescription() {
        var rules = BoundedContextArchitectureRules.forContext(FIXTURE + ".compliant");

        assertThat(rules)
                .extracting(BoundedContextArchitectureRules.SharedRule::id)
                .doesNotHaveDuplicates();
        assertThat(rules)
                .allSatisfy(shared ->
                        assertThat(shared.rule().getDescription()).startsWith("[" + shared.id() + "] compliant｜"));
    }

    @Test
    @DisplayName("錯誤的 base package 不得讓規則全部空轉通過")
    void unknownBasePackageFails() {
        long failures = BoundedContextArchitectureRules.forContext(FIXTURE + ".missing").stream()
                .map(BoundedContextArchitectureRules.SharedRule::rule)
                .filter(rule -> {
                    try {
                        rule.check(FIXTURE_CLASSES);
                        return false;
                    } catch (AssertionError expected) {
                        return true;
                    }
                })
                .count();

        assertThat(failures).isPositive();
    }

    /** 每個違規 fixture 套件與它必須觸發的規則編號。一條規則可以有多個 fixture，涵蓋不同的違規形狀。 */
    private static final List<Violation> VIOLATIONS = List.of(
            new Violation("BC-01", "domainouter"),
            new Violation("BC-02", "applicationouter"),
            new Violation("BC-03", "entrypointinfra"),
            new Violation("BC-04", "infraentrypoint"),
            new Violation("BC-05", "businesscontract"),
            new Violation("BC-05", "businesspublication"),
            new Violation("BC-05", "businesspublisher"),
            new Violation("BC-06", "publisheroutsideadapter"),
            new Violation("BC-06", "businesspublisher"),
            new Violation("BC-07", "messageproducer"),
            new Violation("BC-08", "adapterwithoutport"),
            new Violation("BC-09", "transportname"),
            new Violation("BC-10", "impuretranslator"),
            new Violation("BC-11", "consumerpublishes"),
            new Violation("BC-12", "splitpublisher"),
            new Violation("BC-13", "domaincycle"),
            new Violation("BC-14", "modulecycle"),
            new Violation("BC-15", "nolayer"),
            new Violation("BC-16", "unknowndomainrole"),
            new Violation("BC-16", "domainroot"),
            new Violation("BC-17", "enumoutside"),
            new Violation("BC-17", "nestedenum"),
            new Violation("BC-17", "enumspackagerecord"),
            new Violation("BC-18", "mutablevalue"),
            new Violation("BC-19", "servicesubpackage"),
            new Violation("BC-20", "implsuffix"),
            new Violation("BC-21", "servicename"),
            new Violation("BC-21", "servicemutable"),
            new Violation("BC-22", "serviceimplreferenced"),
            new Violation("BC-23", "serviceunimplemented"),
            new Violation("BC-24", "storeclass"),
            new Violation("BC-24", "storename"),
            new Violation("BC-25", "invocationloose"),
            new Violation("BC-25", "invocationclass"),
            new Violation("BC-26", "entrypointroot"),
            new Violation("BC-26", "entrypointunknown"),
            new Violation("BC-27", "restflat"),
            new Violation("BC-27", "restnotcontroller"),
            new Violation("BC-27", "restresponseclass"),
            new Violation("BC-28", "messagingrole"),
            new Violation("BC-28", "twosubscriptions"),
            new Violation("BC-28", "subscriptionsfield"),
            new Violation("BC-29", "schedulename"),
            new Violation("BC-30", "temporalname"),
            new Violation("BC-31", "modelembeddable"),
            new Violation("BC-31", "primarykeynotembeddedid"),
            new Violation("BC-31", "entityoutsidemodel"),
            new Violation("BC-32", "repositoryname"),
            new Violation("BC-32", "repositorynotjpa"),
            new Violation("BC-33", "storeprefix"),
            new Violation("BC-33", "storeimpl"),
            new Violation("BC-33", "publicstorehelper"),
            new Violation("BC-34", "persistenceunknown"),
            new Violation("BC-35", "mappername"),
            new Violation("BC-36", "applicationroot"),
            new Violation("BC-36", "applicationunknown"),
            new Violation("BC-37", "usecasepackagehelper"),
            new Violation("BC-37", "usecaseoutside"),
            new Violation("BC-38", "usecasetwooperations"),
            new Violation("BC-38", "usecaseidparameter"),
            new Violation("BC-39", "querypublishes"),
            new Violation("BC-39", "querywrites"),
            new Violation("BC-40", "parameterlessfromrest"),
            new Violation("BC-41", "interactorwithoutusecase"),
            new Violation("BC-41", "usecaseusesinteractor"),
            new Violation("BC-42", "servicepackagehelper"),
            new Violation("BC-42", "serviceoutside"),
            new Violation("BC-43", "coordinatorwithoutservice"),
            new Violation("BC-43", "serviceusescoordinator"),
            new Violation("BC-43", "serviceusesusecase"),
            new Violation("BC-44", "entrypointusesstore"),
            new Violation("BC-44", "entrypointusesservice"),
            new Violation("BC-45", "interactorwrites"),
            new Violation("BC-46", "restreturnsstate"),
            new Violation("BC-46", "restwrapsresult"),
            new Violation("BC-47", "eventlistenername"),
            new Violation("BC-48", "applicationeventadaptername"),
            new Violation("BC-48", "applicationeventadapterwithoutport"),
            new Violation("BC-49", "eventlisteneroutsideentrypoint"),
            new Violation("BC-50", "eventpublisheroutsideadapter"));

    @TestFactory
    @DisplayName("每條規則都會擋下對應的違規")
    Stream<DynamicTest> everyRuleRejectsItsViolation() {
        return VIOLATIONS.stream()
                .map(violation -> DynamicTest.dynamicTest(violation.displayName(), violation::assertRejected));
    }

    @Test
    @DisplayName("每條規則都至少有一個違規 fixture，證明它真的會擋")
    void everyRuleHasAViolationFixture() {
        Set<String> covered = VIOLATIONS.stream().map(Violation::ruleId).collect(Collectors.toSet());

        assertThat(BoundedContextArchitectureRules.forContext(FIXTURE + ".compliant"))
                .extracting(BoundedContextArchitectureRules.SharedRule::id)
                .allSatisfy(id ->
                        assertThat(covered).as("violation fixture for %s", id).contains(id));
    }

    private record Violation(String ruleId, String fixture) {

        ArchRule rule() {
            return BoundedContextArchitectureRules.forContext(VIOLATION + fixture).stream()
                    .filter(shared -> shared.id().equals(ruleId))
                    .findFirst()
                    .orElseThrow()
                    .rule();
        }

        String displayName() {
            return fixture + " → " + rule().getDescription();
        }

        void assertRejected() {
            assertThat(rule().evaluate(FIXTURE_CLASSES).hasViolation())
                    .as("%s should reject fixture %s", ruleId, fixture)
                    .isTrue();
        }
    }
}
