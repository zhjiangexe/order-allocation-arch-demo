package com.flowzati.archone.ordering.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Ordering bounded context architecture rules")
class OrderingContextArchitectureTest {

    private static final String PROJECT_PACKAGE = "com.flowzati.archone";
    private static final String CONTEXT_NAME = "ordering";
    private static final String BASE_PACKAGE = PROJECT_PACKAGE + "." + CONTEXT_NAME;
    private static final String INTEGRATION_CONTRACT_PACKAGE = PROJECT_PACKAGE + ".contracts..";
    private static final String ORDERING_INTEGRATION_CONTRACT_PACKAGE = PROJECT_PACKAGE + ".contracts.ordering..";
    private static final String INTEGRATION_EVENT_PUBLISHER =
            PROJECT_PACKAGE + ".messaging.events.IntegrationEventPublisher";
    private static final String DOMAIN_PACKAGE = BASE_PACKAGE + ".domain..";
    private static final String APPLICATION_PACKAGE = BASE_PACKAGE + ".application..";
    private static final String INVOCATION_PACKAGE = BASE_PACKAGE + ".application.invocation..";
    private static final String USECASE_PACKAGE = BASE_PACKAGE + ".application.usecase..";
    private static final String APPLICATION_SERVICE_PACKAGE = BASE_PACKAGE + ".application.service..";
    private static final String APPLICATION_PORT_PACKAGE = BASE_PACKAGE + ".application.port..";
    private static final String APPLICATION_STORE_PACKAGE = BASE_PACKAGE + ".application.store..";
    private static final String ENTRYPOINT_PACKAGE = BASE_PACKAGE + ".entrypoint..";
    private static final String REST_ENTRYPOINT_PACKAGE = BASE_PACKAGE + ".entrypoint.rest..";
    private static final String MESSAGING_ENTRYPOINT_PACKAGE = BASE_PACKAGE + ".entrypoint.messaging..";
    private static final String TEMPORAL_ENTRYPOINT_PACKAGE = BASE_PACKAGE + ".entrypoint.temporal..";
    private static final String INFRASTRUCTURE_PACKAGE = BASE_PACKAGE + ".infrastructure..";
    private static final String MESSAGING_INFRASTRUCTURE_PACKAGE = BASE_PACKAGE + ".infrastructure.messaging..";
    private static final String PERSISTENCE_INFRASTRUCTURE_PACKAGE = BASE_PACKAGE + ".infrastructure.persistence..";
    private static final String JPA_MODEL_PACKAGE = BASE_PACKAGE + ".infrastructure.persistence.jpa.model..";
    private static final String JPA_REPOSITORY_PACKAGE = BASE_PACKAGE + ".infrastructure.persistence.jpa.repository..";
    private static final String JPA_STORE_PACKAGE = BASE_PACKAGE + ".infrastructure.persistence.jpa.store..";
    private static final String JPA_MAPPER_PACKAGE = BASE_PACKAGE + ".infrastructure.persistence.jpa.mapper..";
    private static final String AGGREGATE_PACKAGE = BASE_PACKAGE + ".domain.aggregate..";
    private static final String ENTITY_PACKAGE = BASE_PACKAGE + ".domain.entity..";
    private static final String VALUE_OBJECT_PACKAGE = BASE_PACKAGE + ".domain.valueobject..";
    private static final String DOMAIN_SERVICE_PACKAGE = BASE_PACKAGE + ".domain.service..";

    private static final JavaClasses ORDERING_CLASSES = new ClassFileImporter()
            .importUrl(Order.class.getProtectionDomain().getCodeSource().getLocation());

    @Nested
    @DisplayName("Layer boundaries")
    class LayerBoundaries {

        @Test
        @DisplayName("Domain 不得依賴外層 application、entrypoint 或 infrastructure")
        void domainDoesNotDependOnOuterLayers() {
            noClasses()
                    .that()
                    .resideInAPackage(DOMAIN_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(APPLICATION_PACKAGE, ENTRYPOINT_PACKAGE, INFRASTRUCTURE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application 不得依賴 entrypoint 或 infrastructure")
        void applicationDoesNotDependOnEntrypointsOrInfrastructure() {
            noClasses()
                    .that()
                    .resideInAPackage(APPLICATION_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(ENTRYPOINT_PACKAGE, INFRASTRUCTURE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Domain 與 application 不得依賴 versioned integration event contracts")
        void businessCodeDoesNotDependOnVersionedIntegrationContracts() {
            noClasses()
                    .that()
                    .resideInAnyPackage(DOMAIN_PACKAGE, APPLICATION_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("只有 infrastructure.messaging 可以發布 integration events")
        void onlyMessagingInfrastructureMayPublishIntegrationEvents() {
            noClasses()
                    .that()
                    .resideOutsideOfPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Entrypoint 不得依賴 infrastructure")
        void entrypointsDoNotDependOnInfrastructure() {
            noClasses()
                    .that()
                    .resideInAPackage(ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(INFRASTRUCTURE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }
    }

    @Nested
    @DisplayName("Application layer")
    class ApplicationLayer {

        @Test
        @DisplayName("Application invocation 只能是 top-level record，且名稱以 Command 或 Query 結尾")
        void applicationInvocationsAreCommandsOrQueriesAndRecords() {
            classes()
                    .that()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveNameMatching(".*(Command|Query)$")
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .should()
                    .beRecords()
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application invocation 不得依賴 infrastructure、entrypoint、Spring MVC、JPA 或 integration contract")
        void applicationInvocationsDoNotDependOnTransportOrInfrastructure() {
            noClasses()
                    .that()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ENTRYPOINT_PACKAGE,
                            INFRASTRUCTURE_PACKAGE,
                            "org.springframework.web..",
                            "org.springframework.http..",
                            "jakarta.servlet..",
                            "jakarta.persistence..",
                            INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application usecase package 只能包含 Usecase 或 Interactor")
        void applicationUsecasePackageContainsOnlyUsecasesOrInteractors() {
            classes()
                    .that()
                    .resideInAPackage(USECASE_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveNameMatching(".*(Usecase|Interactor)$")
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application 的 Usecase 與 Interactor 只能位於 usecase package")
        void applicationUsecasesAndInteractorsBelongToUsecasePackage() {
            classes()
                    .that()
                    .resideInAPackage(APPLICATION_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .and()
                    .haveNameMatching(".*(Usecase|Interactor)$")
                    .should()
                    .resideInAPackage(USECASE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Interactor 必須依賴 usecase package 中至少一個 Usecase 或另一個 Interactor")
        void interactorsDependOnUsecasesOrOtherInteractors() {
            classes()
                    .that()
                    .resideInAPackage(USECASE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Interactor")
                    .should()
                    .dependOnClassesThat(resideInAPackage(USECASE_PACKAGE)
                            .and(simpleNameEndingWith("Usecase").or(simpleNameEndingWith("Interactor"))))
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Usecase 不得反向依賴 Interactor")
        void usecasesDoNotDependOnInteractors() {
            noClasses()
                    .that()
                    .resideInAPackage(USECASE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Usecase")
                    .should()
                    .dependOnClassesThat(resideInAPackage(USECASE_PACKAGE).and(simpleNameEndingWith("Interactor")))
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application service package 只能包含 Service 或 Coordinator")
        void applicationServicePackageContainsOnlyServicesOrCoordinators() {
            classes()
                    .that()
                    .resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveNameMatching(".*(Service|Coordinator)$")
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application 的 Service 與 Coordinator 只能位於 service package")
        void applicationServicesAndCoordinatorsBelongToServicePackage() {
            classes()
                    .that()
                    .resideInAPackage(APPLICATION_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .and()
                    .haveNameMatching(".*(Service|Coordinator)$")
                    .should()
                    .resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Coordinator 必須依賴 service package 中至少一個 Service 或另一個 Coordinator")
        void coordinatorsDependOnServicesOrOtherCoordinators() {
            classes()
                    .that()
                    .resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Coordinator")
                    .should()
                    .dependOnClassesThat(resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                            .and(simpleNameEndingWith("Service").or(simpleNameEndingWith("Coordinator"))))
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Service 不得反向依賴 Coordinator")
        void servicesDoNotDependOnCoordinators() {
            noClasses()
                    .that()
                    .resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Service")
                    .should()
                    .dependOnClassesThat(
                            resideInAPackage(APPLICATION_SERVICE_PACKAGE).and(simpleNameEndingWith("Coordinator")))
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application service package 不得反向依賴 usecase package")
        void applicationServicesDoNotDependOnUsecases() {
            noClasses()
                    .that()
                    .resideInAPackage(APPLICATION_SERVICE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(USECASE_PACKAGE)
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Application usecase 不得依賴 transport DTO 或 infrastructure implementation")
        void applicationUsecasesDoNotDependOnTransportOrInfrastructure() {
            noClasses()
                    .that()
                    .resideInAPackage(USECASE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ENTRYPOINT_PACKAGE,
                            INFRASTRUCTURE_PACKAGE,
                            "org.springframework.web..",
                            "org.springframework.http..",
                            "jakarta.servlet..",
                            INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }
    }

    @Nested
    @DisplayName("Domain layer")
    class DomainLayer {

        @Test
        @DisplayName("Domain 不得依賴 Spring、JPA、messaging 或 integration contracts")
        void domainDoesNotDependOnFrameworksOrIntegrationBoundaries() {
            noClasses()
                    .that()
                    .resideInAPackage(DOMAIN_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            PROJECT_PACKAGE + ".messaging..",
                            INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Domain value object 必須是 record")
        void domainValueObjectsAreRecords() {
            classes()
                    .that()
                    .resideInAPackage(VALUE_OBJECT_PACKAGE)
                    .should()
                    .beRecords()
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Domain service 必須以 Service 結尾")
        void domainServicesHaveServiceSuffix() {
            classes()
                    .that()
                    .resideInAPackage(DOMAIN_SERVICE_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("Service")
                    .allowEmptyShould(true)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Aggregate 不得依賴 domain service")
        void aggregatesDoNotDependOnDomainServices() {
            noClasses()
                    .that()
                    .resideInAPackage(AGGREGATE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(DOMAIN_SERVICE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Entity 不得依賴 aggregate 或 domain service")
        void entitiesDoNotDependOnAggregatesOrDomainServices() {
            noClasses()
                    .that()
                    .resideInAPackage(ENTITY_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(AGGREGATE_PACKAGE, DOMAIN_SERVICE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Value object 不得依賴 aggregate、entity 或 domain service")
        void valueObjectsDoNotDependOnOtherDomainObjectsOrServices() {
            noClasses()
                    .that()
                    .resideInAPackage(VALUE_OBJECT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(AGGREGATE_PACKAGE, ENTITY_PACKAGE, DOMAIN_SERVICE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Domain subpackages 不得形成循環依賴")
        void domainSubpackagesAreFreeOfCycles() {
            slices().matching(BASE_PACKAGE + ".domain.(*)..")
                    .should()
                    .beFreeOfCycles()
                    .check(ORDERING_CLASSES);
        }
    }

    @Nested
    @DisplayName("Entrypoint layer")
    class EntrypointLayer {

        @Test
        @DisplayName("REST entrypoint 的 top-level class 只能是 Rest、Request 或 Response")
        void restEntrypointsHaveHttpBoundaryNames() {
            classes()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveNameMatching(".*(Rest|Request|Response)$")
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("REST Request 與 Response 只能位於 HTTP entrypoint")
        void restRequestsAndResponsesBelongToHttpEntrypoints() {
            classes()
                    .that()
                    .haveSimpleNameEndingWith("Request")
                    .or()
                    .haveSimpleNameEndingWith("Response")
                    .should()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("REST Request 與 Response 必須是 record")
        void restRequestsAndResponsesAreRecords() {
            classes()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .and()
                    .haveNameMatching(".*(Request|Response)$")
                    .should()
                    .beRecords()
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("REST controller 必須以 Rest 結尾並位於 REST entrypoint")
        void restControllersHaveTheExpectedNameAndPackage() {
            classes()
                    .that()
                    .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .andShould()
                    .haveSimpleNameEndingWith("Rest")
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Rest")
                    .should()
                    .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("REST adapter 必須依賴 application invocation，且不得直接碰 persistence 或 event publisher")
        void restAdaptersTranslateToInvocationsAndDoNotBypassApplicationPorts() {
            classes()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Rest")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            PERSISTENCE_INFRASTRUCTURE_PACKAGE, APPLICATION_PORT_PACKAGE, APPLICATION_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Messaging entrypoint 必須以 EventConsumer 結尾")
        void messagingEntrypointsHaveConsumerNames() {
            classes()
                    .that()
                    .resideInAPackage(MESSAGING_ENTRYPOINT_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("EventConsumer")
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Messaging entrypoint 必須把 inbound event 轉成 application invocation")
        void messagingEntrypointsTranslateToInvocations() {
            classes()
                    .that()
                    .resideInAPackage(MESSAGING_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(MESSAGING_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat(resideInAPackage(USECASE_PACKAGE)
                            .and(simpleNameEndingWith("Usecase").or(simpleNameEndingWith("Interactor"))))
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Messaging entrypoint 不得直接組 outbound integration event")
        void messagingEntrypointsDoNotPublishOutboundIntegrationEvents() {
            noClasses()
                    .that()
                    .resideInAPackage(MESSAGING_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            MESSAGING_INFRASTRUCTURE_PACKAGE,
                            PERSISTENCE_INFRASTRUCTURE_PACKAGE,
                            APPLICATION_PORT_PACKAGE,
                            APPLICATION_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Temporal entrypoint 必須是 Adapter，並轉成 application invocation")
        void temporalEntrypointsAreAdaptersThatTranslateToInvocations() {
            classes()
                    .that()
                    .resideInAPackage(TEMPORAL_ENTRYPOINT_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("Adapter")
                    .andShould()
                    .dependOnClassesThat()
                    .resideInAPackage(INVOCATION_PACKAGE)
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(TEMPORAL_ENTRYPOINT_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .dependOnClassesThat(resideInAPackage(USECASE_PACKAGE)
                            .and(simpleNameEndingWith("Usecase").or(simpleNameEndingWith("Interactor"))))
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Temporal entrypoint 不得繞過 application boundary")
        void temporalEntrypointsDoNotBypassApplicationBoundary() {
            noClasses()
                    .that()
                    .resideInAPackage(TEMPORAL_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(INFRASTRUCTURE_PACKAGE, APPLICATION_PORT_PACKAGE, APPLICATION_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("不同 entrypoint technology package 不得互相依賴")
        void entrypointTechnologyPackagesDoNotDependOnEachOther() {
            noClasses()
                    .that()
                    .resideInAPackage(REST_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(MESSAGING_ENTRYPOINT_PACKAGE, TEMPORAL_ENTRYPOINT_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(MESSAGING_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(REST_ENTRYPOINT_PACKAGE, TEMPORAL_ENTRYPOINT_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(TEMPORAL_ENTRYPOINT_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(REST_ENTRYPOINT_PACKAGE, MESSAGING_ENTRYPOINT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }
    }

    @Nested
    @DisplayName("Infrastructure layer")
    class InfrastructureLayer {

        @Test
        @DisplayName("Infrastructure 不得反向依賴 entrypoint")
        void infrastructureDoesNotDependOnEntrypoints() {
            noClasses()
                    .that()
                    .resideInAPackage(INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(ENTRYPOINT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Messaging infrastructure 只能包含 IntegrationEventAdapter、Translator 或 Resolver")
        void messagingInfrastructureContainsOnlyBoundaryRoles() {
            classes()
                    .that()
                    .resideInAPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveNameMatching(".*(IntegrationEventAdapter|Translator|Resolver)$")
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Integration event adapter 必須實作 application publisher port 並負責實際發布")
        void integrationEventAdaptersImplementPublisherPortsAndPublishEvents() {
            classes()
                    .that()
                    .resideInAPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("IntegrationEventAdapter")
                    .should()
                    .implement(resideInAPackage(APPLICATION_PORT_PACKAGE).and(simpleNameEndingWith("Publisher")))
                    .andShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Messaging helper 不得繞過 IntegrationEventAdapter 直接發布 event")
        void onlyIntegrationEventAdaptersPublishFromMessagingInfrastructure() {
            noClasses()
                    .that()
                    .resideInAPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .haveSimpleNameNotEndingWith("IntegrationEventAdapter")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Ordering outbound integration contracts 只能由 messaging infrastructure 使用")
        void onlyMessagingInfrastructureDependsOnOrderingIntegrationContracts() {
            noClasses()
                    .that()
                    .resideOutsideOfPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(ORDERING_INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Persistence infrastructure 不得依賴 messaging 或 integration contracts")
        void persistenceInfrastructureDoesNotDependOnMessagingBoundaries() {
            noClasses()
                    .that()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(PROJECT_PACKAGE + ".messaging..", INTEGRATION_CONTRACT_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("JPA model 必須以 Entity 結尾並標註 @Entity")
        void jpaModelsAreEntities() {
            classes()
                    .that()
                    .resideInAPackage(JPA_MODEL_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("Entity")
                    .andShould()
                    .beAnnotatedWith("jakarta.persistence.Entity")
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .areAnnotatedWith("jakarta.persistence.Entity")
                    .should()
                    .resideInAPackage(JPA_MODEL_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("JPA repository 必須是 repository package 中的 Repository interface")
        void jpaRepositoriesAreRepositoryInterfaces() {
            classes()
                    .that()
                    .resideInAPackage(JPA_REPOSITORY_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("Repository")
                    .andShould()
                    .beInterfaces()
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Repository")
                    .should()
                    .resideInAPackage(JPA_REPOSITORY_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Persistence store adapter 必須實作 application store port")
        void persistenceStoreAdaptersImplementApplicationStorePorts() {
            classes()
                    .that()
                    .resideInAPackage(JPA_STORE_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("StoreAdapter")
                    .andShould()
                    .implement(resideInAPackage(APPLICATION_STORE_PACKAGE))
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("StoreAdapter")
                    .should()
                    .resideInAPackage(JPA_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("JPA mapper 必須位於 mapper package 並以 Mapper 結尾")
        void jpaMappersBelongToMapperPackage() {
            classes()
                    .that()
                    .resideInAPackage(JPA_MAPPER_PACKAGE)
                    .and()
                    .areTopLevelClasses()
                    .should()
                    .haveSimpleNameEndingWith("Mapper")
                    .check(ORDERING_CLASSES);

            classes()
                    .that()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .and()
                    .haveSimpleNameEndingWith("Mapper")
                    .should()
                    .resideInAPackage(JPA_MAPPER_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("JPA model、repository、mapper 與 store 的依賴方向不得反轉")
        void jpaPackagesFollowPersistenceDependencyDirection() {
            noClasses()
                    .that()
                    .resideInAPackage(JPA_MODEL_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(JPA_REPOSITORY_PACKAGE, JPA_MAPPER_PACKAGE, JPA_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(JPA_REPOSITORY_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(JPA_MAPPER_PACKAGE, JPA_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(JPA_MAPPER_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(JPA_REPOSITORY_PACKAGE, JPA_STORE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }

        @Test
        @DisplayName("Persistence adapter 與 messaging adapter 不得互相依賴")
        void persistenceAndMessagingAdaptersDoNotDependOnEachOther() {
            noClasses()
                    .that()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .check(ORDERING_CLASSES);

            noClasses()
                    .that()
                    .resideInAPackage(MESSAGING_INFRASTRUCTURE_PACKAGE)
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(PERSISTENCE_INFRASTRUCTURE_PACKAGE)
                    .check(ORDERING_CLASSES);
        }
    }
}
