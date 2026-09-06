package com.flowzati.archone.orderfulfillment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationCoordinator;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OrderFulfillmentProcessArchitectureTest {

    private static final JavaClasses PROCESS_CLASSES = new ClassFileImporter()
            .importUrl(FulfillmentCancellationCoordinator.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @Test
    void applicationDoesNotDependOnOuterPackages() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.flowzati.archone.orderfulfillment.orchestration..",
                        "com.flowzati.archone.orderfulfillment.entrypoint..",
                        "com.flowzati.archone.orderfulfillment.configuration..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void entrypointsDoNotDependOnOrchestrationOrConfiguration() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.entrypoint..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.flowzati.archone.orderfulfillment.orchestration..",
                        "com.flowzati.archone.orderfulfillment.configuration..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void orchestrationDriversDoNotDependOnEachOther() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.orchestration.eventdriven..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.orchestration.temporal..")
                .check(PROCESS_CLASSES);

        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.orchestration.temporal..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.orderfulfillment.orchestration.eventdriven..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void processDoesNotDependOnDeploymentPackages() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.flowzati.archone.bootstrap..", "com.flowzati.archone.demo..")
                .check(PROCESS_CLASSES);
    }
}
