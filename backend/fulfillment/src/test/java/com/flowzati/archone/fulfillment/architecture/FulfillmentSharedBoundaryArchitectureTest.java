package com.flowzati.archone.fulfillment.architecture;

import com.flowzati.archone.architecture.BoundedContextArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@DisplayName("Fulfillment shared boundary rules")
class FulfillmentSharedBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importUrl(com.flowzati.archone.fulfillment.application.usecase.AcceptCancellationRequestUsecase.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @TestFactory
    Stream<DynamicTest> sharedBoundaryRules() {
        return BoundedContextArchitectureRules.checkedRulesFor("com.flowzati.archone.fulfillment").stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getDescription(), () -> rule.check(CLASSES)));
    }
}
