package com.flowzati.archone.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Function;
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

    @TestFactory
    @DisplayName("每條規則都會擋下對應的違規")
    Stream<DynamicTest> everyRuleRejectsItsViolation() {
        return Stream.of(
                        violation("domainouter", BoundedContextArchitectureRules::domainDoesNotDependOnOuterLayers),
                        violation(
                                "applicationouter",
                                BoundedContextArchitectureRules::applicationDoesNotDependOnEntrypointsOrInfrastructure),
                        violation(
                                "entrypointinfra",
                                BoundedContextArchitectureRules::entrypointsDoNotDependOnInfrastructure),
                        violation(
                                "infraentrypoint",
                                BoundedContextArchitectureRules::infrastructureDoesNotDependOnEntrypoints),
                        violation(
                                "businesscontract",
                                BoundedContextArchitectureRules::businessCodeDoesNotDependOnIntegrationEventBoundary),
                        violation(
                                "businesspublication",
                                BoundedContextArchitectureRules::businessCodeDoesNotDependOnIntegrationEventBoundary),
                        violation(
                                "businesspublisher",
                                BoundedContextArchitectureRules::businessCodeDoesNotDependOnIntegrationEventBoundary),
                        violation(
                                "publisheroutsideadapter",
                                BoundedContextArchitectureRules
                                        ::onlyIntegrationEventAdaptersUseIntegrationEventPublisher),
                        violation(
                                "businesspublisher",
                                BoundedContextArchitectureRules
                                        ::onlyIntegrationEventAdaptersUseIntegrationEventPublisher),
                        violation(
                                "messageproducer", BoundedContextArchitectureRules::noClassUsesMessageProducerDirectly),
                        violation(
                                "adapterwithoutport",
                                BoundedContextArchitectureRules
                                        ::integrationEventAdaptersImplementPublisherPortsAndPublish),
                        violation(
                                "transportname",
                                BoundedContextArchitectureRules::integrationEventAdaptersAreNotNamedAfterTransport),
                        violation(
                                "impuretranslator", BoundedContextArchitectureRules::translatorsArePureTransformations),
                        violation(
                                "consumerpublishes",
                                BoundedContextArchitectureRules::entrypointsDoNotPublishOutboundEvents),
                        violation(
                                "splitpublisher",
                                BoundedContextArchitectureRules::publisherPortsReceiveOneCompleteEvent),
                        violation(
                                "domaincycle",
                                BoundedContextArchitectureRules::domainSubpackagesOfEachModuleAreFreeOfCycles),
                        violation("modulecycle", BoundedContextArchitectureRules::modulesAreFreeOfCycles),
                        violation("nolayer", BoundedContextArchitectureRules::everyClassBelongsToALayer))
                .map(violation -> DynamicTest.dynamicTest(violation.displayName(), violation::assertRejected));
    }

    private static Violation violation(String fixture, Function<BoundedContextArchitectureRules, ArchRule> rule) {
        return new Violation(fixture, rule.apply(new BoundedContextArchitectureRules(VIOLATION + fixture)));
    }

    private record Violation(String fixture, ArchRule rule) {

        String displayName() {
            return fixture + " → " + rule.getDescription();
        }

        void assertRejected() {
            assertThat(rule.evaluate(FIXTURE_CLASSES).hasViolation())
                    .as("%s should reject fixture %s", rule.getDescription(), fixture)
                    .isTrue();
        }
    }
}
