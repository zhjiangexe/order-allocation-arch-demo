package com.flowzati.archone.logisticsdata.architecture;

import com.flowzati.archone.architecture.BoundedContextArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@DisplayName("Logistics data shared boundary rules")
class LogisticsDataSharedBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importUrl(com.flowzati.archone.logisticsdata.application.usecase.ListOwnersUsecase.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @TestFactory
    Stream<DynamicTest> sharedBoundaryRules() {
        return BoundedContextArchitectureRules.checkedRulesFor("com.flowzati.archone.logisticsdata").stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getDescription(), () -> rule.check(CLASSES)));
    }
}
