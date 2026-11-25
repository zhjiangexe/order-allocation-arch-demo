package com.flowzati.archone.ordering.architecture;

import com.flowzati.archone.architecture.BoundedContextArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@DisplayName("Ordering shared boundary rules")
class OrderingSharedBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importUrl(com.flowzati.archone.ordering.domain.aggregate.Order.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @TestFactory
    Stream<DynamicTest> sharedBoundaryRules() {
        return BoundedContextArchitectureRules.checkedRulesFor("com.flowzati.archone.ordering").stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getDescription(), () -> rule.check(CLASSES)));
    }
}
