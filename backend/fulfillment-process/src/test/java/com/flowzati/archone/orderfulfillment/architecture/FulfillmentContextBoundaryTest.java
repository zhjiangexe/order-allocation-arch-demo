package com.flowzati.archone.orderfulfillment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.flowzati.archone.orderfulfillment")
class FulfillmentContextBoundaryTest {

    @ArchTest
    static final ArchRule fulfillmentDependsOnlyOnPublicContextApis = noClasses()
            .that()
            .resideInAPackage("com.flowzati.archone.orderfulfillment..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.flowzati.archone.ordering.application..",
                    "com.flowzati.archone.ordering.domain..",
                    "com.flowzati.archone.ordering.entrypoint..",
                    "com.flowzati.archone.ordering.infrastructure..",
                    "com.flowzati.archone.inventory..",
                    "com.flowzati.archone.wms.shipment..",
                    "com.flowzati.archone.wms.process..",
                    "com.flowzati.archone.wms.infrastructure..");
}
