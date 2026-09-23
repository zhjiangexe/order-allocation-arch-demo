package com.flowzati.archone.fulfillment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.flowzati.archone.fulfillment")
class FulfillmentContextBoundaryTest {

    @ArchTest
    static final ArchRule fulfillmentDependsOnlyOnPublicContextApis = noClasses()
            .that()
            .resideInAPackage("com.flowzati.archone.fulfillment..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.flowzati.archone.ordering.application..",
                    "com.flowzati.archone.ordering.domain..",
                    "com.flowzati.archone.ordering.entrypoint..",
                    "com.flowzati.archone.ordering.infrastructure..",
                    "com.flowzati.archone.inventory.allocation..",
                    "com.flowzati.archone.inventory.balance..",
                    "com.flowzati.archone.inventory.bootstrap..",
                    "com.flowzati.archone.inventory.location..",
                    "com.flowzati.archone.inventory.movement..",
                    "com.flowzati.archone.wms.configuration..",
                    "com.flowzati.archone.wms.dispatch..",
                    "com.flowzati.archone.wms.picking..",
                    "com.flowzati.archone.wms.shipment..",
                    "com.flowzati.archone.wms.process..",
                    "com.flowzati.archone.wms.infrastructure..",
                    "com.flowzati.archone.wms.receiving..",
                    "com.flowzati.archone.wms.wave..");
}
