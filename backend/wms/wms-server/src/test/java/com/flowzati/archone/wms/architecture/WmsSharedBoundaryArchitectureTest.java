package com.flowzati.archone.wms.architecture;

import com.flowzati.archone.architecture.BoundedContextArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@DisplayName("WMS shared boundary rules")
class WmsSharedBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importUrl(com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @TestFactory
    Stream<DynamicTest> sharedBoundaryRules() {
        return BoundedContextArchitectureRules.checkedRulesFor("com.flowzati.archone.wms").stream()
                .map(rule -> DynamicTest.dynamicTest(rule.getDescription(), () -> rule.check(CLASSES)));
    }
}
