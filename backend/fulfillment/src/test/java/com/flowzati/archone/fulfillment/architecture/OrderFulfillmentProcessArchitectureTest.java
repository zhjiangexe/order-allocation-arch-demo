package com.flowzati.archone.fulfillment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.flowzati.archone.fulfillment.application.usecase.AcceptCancellationRequestUsecase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class OrderFulfillmentProcessArchitectureTest {

    private static final JavaClasses PROCESS_CLASSES = new ClassFileImporter()
            .importUrl(AcceptCancellationRequestUsecase.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation());

    @Test
    void applicationDoesNotDependOnOuterPackages() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.flowzati.archone.fulfillment.orchestration..",
                        "com.flowzati.archone.fulfillment.entrypoint..",
                        "com.flowzati.archone.fulfillment.configuration..",
                        "com.flowzati.archone.fulfillment.infrastructure..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void applicationDoesNotDependOnTemporalSdk() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.temporal..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void cancellationApplicationDoesNotDependOnTemporalInvocationContracts() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.application.usecase..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.orchestration.contract.workflow.order.invocation..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void entrypointsDoNotDependOnOrchestrationOrConfiguration() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.entrypoint..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.flowzati.archone.fulfillment.orchestration..",
                        "com.flowzati.archone.fulfillment.configuration..")
                .check(PROCESS_CLASSES);
    }

    @Test
    void orchestrationDriversDoNotDependOnEachOther() {
        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.infrastructure.messaging..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.fulfillment.infrastructure.temporal..")
                .check(PROCESS_CLASSES);

        noClasses()
                .that()
                .resideInAPackage("com.flowzati.archone.fulfillment.infrastructure.temporal..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.flowzati.archone.fulfillment.infrastructure.messaging..")
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
