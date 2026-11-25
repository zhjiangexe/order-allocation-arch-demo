package com.flowzati.archone.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Repository-wide layer and Integration Event boundary rules shared by every bounded context.
 *
 * <p>Package patterns use {@code base..layer..}, so module-first contexts such as
 * {@code inventory.movement.application} and single-module contexts such as {@code ordering.application} follow the same
 * rules. Context-specific naming and role rules stay in each context's own architecture test.
 *
 * <p>The application rule intentionally fails on an empty import, so a wrong base package or class location cannot
 * make every other rule pass vacuously. A context without a domain, entrypoint or infrastructure layer is allowed.
 *
 * <p>Every rule carries a stable identifier such as {@code BC-01}. The identifier, the rationale and the correct way to
 * fix a violation are documented in this module's {@code README.md}; rule descriptions start with the identifier so a
 * failing test report points straight to that table.
 */
public final class BoundedContextArchitectureRules {

    public static final String INTEGRATION_CONTRACT_PACKAGE = "com.flowzati.archone.contracts..";
    public static final String INTEGRATION_EVENT_PUBLISHER =
            "com.flowzati.archone.messaging.events.IntegrationEventPublisher";
    public static final String INTEGRATION_EVENT_PUBLICATION =
            "com.flowzati.archone.messaging.events.IntegrationEventPublication";
    public static final String MESSAGE_PRODUCER = "com.flowzati.archone.messaging.api.MessageProducer";

    private static final Set<String> LAYERS =
            Set.of("domain", "application", "entrypoint", "infrastructure", "bootstrap");

    private final String basePackage;
    private final String contextName;
    private final String context;
    private final String domain;
    private final String application;
    private final String applicationPort;
    private final String applicationStore;
    private final String entrypoint;
    private final String infrastructure;
    private final String messagingInfrastructure;
    private final String persistenceInfrastructure;

    BoundedContextArchitectureRules(String basePackage) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage");
        contextName = contextNameOf(basePackage);
        context = basePackage + "..";
        domain = basePackage + "..domain..";
        application = basePackage + "..application..";
        applicationPort = basePackage + "..application.port..";
        applicationStore = basePackage + "..application.store..";
        entrypoint = basePackage + "..entrypoint..";
        infrastructure = basePackage + "..infrastructure..";
        messagingInfrastructure = basePackage + "..infrastructure.messaging..";
        persistenceInfrastructure = basePackage + "..infrastructure.persistence..";
    }

    /**
     * Rules whose existing violations are frozen because the fix was explicitly deferred, keyed by context name.
     *
     * <p>The owning context's own architecture test checks these with {@link FreezingArchRule}: existing violations
     * recorded in that module's {@code src/test/resources/archunit-frozen-violations} pass, any new violation fails,
     * and fixed violations disappear from the store. Every entry must have a row in the README's deferred-work table.
     */
    public static final Map<String, Set<String>> DEFERRED_RULES = Map.of(
            // inventory 模組間既有循環：重整方案已定案，2026-11-25 決定延後執行。
            "inventory", Set.of("BC-14"));

    /** A shared rule and its stable identifier from the rule table in {@code README.md}. */
    public record SharedRule(String id, ArchRule rule) {}

    /** All shared rules for the bounded context rooted at {@code basePackage}. */
    public static List<SharedRule> forContext(String basePackage) {
        BoundedContextArchitectureRules rules = new BoundedContextArchitectureRules(basePackage);
        BoundedContextRoleRules roles = new BoundedContextRoleRules(basePackage);
        BoundedContextApplicationRules app = new BoundedContextApplicationRules(basePackage);
        return List.of(
                new SharedRule("BC-01", rules.domainDoesNotDependOnOuterLayers()),
                new SharedRule("BC-02", rules.applicationDoesNotDependOnEntrypointsOrInfrastructure()),
                new SharedRule("BC-03", rules.entrypointsDoNotDependOnInfrastructure()),
                new SharedRule("BC-04", rules.infrastructureDoesNotDependOnEntrypoints()),
                new SharedRule("BC-05", rules.businessCodeDoesNotDependOnIntegrationEventBoundary()),
                new SharedRule("BC-06", rules.onlyIntegrationEventAdaptersUseIntegrationEventPublisher()),
                new SharedRule("BC-07", rules.noClassUsesMessageProducerDirectly()),
                new SharedRule("BC-08", rules.integrationEventAdaptersImplementPublisherPortsAndPublish()),
                new SharedRule("BC-09", rules.integrationEventAdaptersAreNotNamedAfterTransport()),
                new SharedRule("BC-10", rules.translatorsArePureTransformations()),
                new SharedRule("BC-11", rules.entrypointsDoNotPublishOutboundEvents()),
                new SharedRule("BC-12", rules.publisherPortsReceiveOneCompleteEvent()),
                new SharedRule("BC-13", rules.domainSubpackagesOfEachModuleAreFreeOfCycles()),
                new SharedRule("BC-14", rules.modulesAreFreeOfCycles()),
                new SharedRule("BC-15", rules.everyClassBelongsToALayer()),
                new SharedRule("BC-16", roles.domainClassesUseKnownRolePackages()),
                new SharedRule("BC-17", roles.enumsLiveInEnumsPackages()),
                new SharedRule("BC-18", roles.valueObjectsAreImmutableValues()),
                new SharedRule("BC-19", roles.domainServicePackageIsFlat()),
                new SharedRule("BC-20", roles.noImplSuffix()),
                new SharedRule("BC-21", roles.domainServiceImplementationsAreNamedAndImmutable()),
                new SharedRule("BC-22", roles.domainServiceImplementationsAreReferencedOnlyFromConfiguration()),
                new SharedRule("BC-23", roles.domainServiceInterfacesHaveImplementations()),
                new SharedRule("BC-24", roles.applicationStoresArePureInterfaces()),
                new SharedRule("BC-25", roles.invocationsAreCommandsQueriesOrTheirComponents()),
                new SharedRule("BC-26", roles.entrypointsUseKnownChannelPackages()),
                new SharedRule("BC-27", roles.restPackagesSeparateControllersRequestsAndResponses()),
                new SharedRule("BC-28", roles.messagingEntrypointsAreConsumersAndOneSubscriptionsHolder()),
                new SharedRule("BC-29", roles.scheduleEntrypointsAreSchedulers()),
                new SharedRule("BC-30", roles.temporalEntrypointsAreAdapters()),
                new SharedRule("BC-31", roles.jpaModelsAreEntitiesOrPrimaryKeys()),
                new SharedRule("BC-32", roles.jpaRepositoriesExtendJpaRepository()),
                new SharedRule("BC-33", roles.storeAdaptersAreNamedAfterTheirStore()),
                new SharedRule("BC-34", roles.persistenceUsesKnownPackages()),
                new SharedRule("BC-35", roles.jpaMappersAreMappers()),
                new SharedRule("BC-36", app.applicationClassesUseKnownRolePackages()),
                new SharedRule("BC-37", app.usecasePackageHoldsUsecasesAndInteractors()),
                new SharedRule("BC-38", app.usecaseOperationsReceiveOneCommand()),
                new SharedRule("BC-39", app.queryUsecasesAreReadOnly()),
                new SharedRule("BC-40", app.parameterlessOperationsAreTriggeredBySchedulers()),
                new SharedRule("BC-41", app.interactorsComposeUsecases()),
                new SharedRule("BC-42", app.servicePackageHoldsServicesAndCoordinators()),
                new SharedRule("BC-43", app.servicesStayBelowUsecases()),
                new SharedRule("BC-44", app.entrypointsEnterThroughUsecases()),
                new SharedRule("BC-45", app.interactorsOnlyRead()),
                new SharedRule("BC-46", roles.restOperationsReturnResponses()),
                new SharedRule("BC-47", roles.eventEntrypointsAreListeners()),
                new SharedRule("BC-48", roles.applicationEventAdaptersImplementPublisherPorts()),
                new SharedRule("BC-49", roles.applicationEventsAreHandledOnlyInEventEntrypoints()),
                new SharedRule("BC-50", roles.applicationEventsArePublishedOnlyByEventAdapters()));
    }

    /**
     * The rules a context's own architecture test checks: every shared rule, with the context's deferred rules wrapped
     * in {@link FreezingArchRule}.
     */
    public static List<ArchRule> checkedRulesFor(String basePackage) {
        Set<String> deferred = DEFERRED_RULES.getOrDefault(contextNameOf(basePackage), Set.of());
        return forContext(basePackage).stream()
                .map(shared -> deferred.contains(shared.id()) ? FreezingArchRule.freeze(shared.rule()) : shared.rule())
                .toList();
    }

    /** Whether the rule is deferred for the context and therefore checked only by that context's own test. */
    public static boolean isDeferred(String basePackage, String ruleId) {
        return DEFERRED_RULES.getOrDefault(contextNameOf(basePackage), Set.of()).contains(ruleId);
    }

    private static String contextNameOf(String basePackage) {
        return basePackage.substring(basePackage.lastIndexOf('.') + 1);
    }

    private String describe(String id, String text) {
        return "[" + id + "] " + contextName + "｜" + text;
    }

    ArchRule domainDoesNotDependOnOuterLayers() {
        return noClasses()
                .that()
                .resideInAPackage(domain)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(application, entrypoint, infrastructure)
                .allowEmptyShould(true)
                .as(describe("BC-01", "Domain 不得依賴 application、entrypoint 或 infrastructure"));
    }

    ArchRule applicationDoesNotDependOnEntrypointsOrInfrastructure() {
        return noClasses()
                .that()
                .resideInAPackage(application)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(entrypoint, infrastructure)
                .as(describe("BC-02", "Application 不得依賴 entrypoint 或 infrastructure"));
    }

    ArchRule entrypointsDoNotDependOnInfrastructure() {
        return noClasses()
                .that()
                .resideInAPackage(entrypoint)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(infrastructure)
                .allowEmptyShould(true)
                .as(describe("BC-03", "Entrypoint 不得依賴 infrastructure"));
    }

    ArchRule infrastructureDoesNotDependOnEntrypoints() {
        return noClasses()
                .that()
                .resideInAPackage(infrastructure)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(entrypoint)
                .allowEmptyShould(true)
                .as(describe("BC-04", "Infrastructure 不得依賴 entrypoint"));
    }

    ArchRule businessCodeDoesNotDependOnIntegrationEventBoundary() {
        return noClasses()
                .that()
                .resideInAnyPackage(domain, application)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(INTEGRATION_CONTRACT_PACKAGE)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLICATION)
                .as(describe(
                        "BC-05",
                        "Domain 與 application 不得依賴 versioned integration contract、"
                                + "IntegrationEventPublication 或 IntegrationEventPublisher"));
    }

    ArchRule onlyIntegrationEventAdaptersUseIntegrationEventPublisher() {
        return noClasses()
                .that(resideInAPackage(context)
                        .and(simpleNameEndingWith("IntegrationEventAdapter")
                                .and(resideInAPackage(messagingInfrastructure))
                                .negate()))
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                .as(describe(
                        "BC-06",
                        "只有 infrastructure.messaging 的 *IntegrationEventAdapter 可以使用 IntegrationEventPublisher"));
    }

    ArchRule noClassUsesMessageProducerDirectly() {
        return noClasses()
                .that()
                .resideInAPackage(context)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(MESSAGE_PRODUCER)
                .as(describe("BC-07", "Bounded context 不得直接使用 MessageProducer"));
    }

    ArchRule integrationEventAdaptersImplementPublisherPortsAndPublish() {
        return classes()
                .that()
                .resideInAPackage(context)
                .and()
                .haveSimpleNameEndingWith("IntegrationEventAdapter")
                .should()
                .resideInAPackage(messagingInfrastructure)
                .andShould()
                .implement(resideInAPackage(applicationPort).and(simpleNameEndingWith("Publisher")))
                .andShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                .allowEmptyShould(true)
                .as(describe(
                        "BC-08",
                        "*IntegrationEventAdapter 必須位於 infrastructure.messaging、實作 application.port 的 *Publisher，"
                                + "並交給 IntegrationEventPublisher 發布"));
    }

    ArchRule integrationEventAdaptersAreNotNamedAfterTransport() {
        return noClasses()
                .that()
                .resideInAPackage(context)
                .and()
                .haveSimpleNameEndingWith("IntegrationEventAdapter")
                .should()
                .haveNameMatching(".*(Outbox|Kafka|Jdbc|JDBC)[^.]*$")
                .allowEmptyShould(true)
                .as(describe("BC-09", "*IntegrationEventAdapter 不得以 Outbox、Kafka、JDBC 等下層傳輸機制命名"));
    }

    ArchRule translatorsArePureTransformations() {
        return noClasses()
                .that()
                .resideInAPackage(messagingInfrastructure)
                .and()
                .haveSimpleNameEndingWith("Translator")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(MESSAGE_PRODUCER)
                .orShould()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        applicationStore,
                        persistenceInfrastructure,
                        "org.springframework.jdbc..",
                        "org.springframework.data..",
                        "jakarta.persistence..")
                .allowEmptyShould(true)
                .as(describe("BC-10", "*Translator 必須是純轉換：不得發布、不得查詢或寫入資料庫"));
    }

    ArchRule entrypointsDoNotPublishOutboundEvents() {
        return noClasses()
                .that()
                .resideInAPackage(entrypoint)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLISHER)
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(INTEGRATION_EVENT_PUBLICATION)
                .orShould()
                .dependOnClassesThat(resideInAPackage(applicationPort).and(simpleNameEndingWith("Publisher")))
                .allowEmptyShould(true)
                .as(describe("BC-11", "Entrypoint（含 Consumer）不得自行發布 outbound event，必須交給 Usecase"));
    }

    ArchRule publisherPortsReceiveOneCompleteEvent() {
        return methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(applicationPort)
                .and()
                .areDeclaredInClassesThat()
                .haveSimpleNameEndingWith("Publisher")
                .and()
                .areDeclaredInClassesThat()
                .areInterfaces()
                .should(receiveExactlyOneParameter())
                .allowEmptyShould(true)
                .as(describe("BC-12", "application.port 的 *Publisher 每個 method 只能接收一個完整 Application Event"));
    }

    ArchRule domainSubpackagesOfEachModuleAreFreeOfCycles() {
        return slices().assignedFrom(new SliceAssignment() {
                    @Override
                    public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                        String packageName = javaClass.getPackageName();
                        int domainIndex = packageName.indexOf(".domain.", basePackage.length());
                        if (!packageName.startsWith(basePackage + ".") || domainIndex < 0) {
                            return SliceIdentifier.ignore();
                        }
                        // Single-module contexts have the domain layer directly under the base package.
                        String module = domainIndex > basePackage.length()
                                ? packageName.substring(basePackage.length() + 1, domainIndex)
                                : "";
                        String rest = packageName.substring(domainIndex + ".domain.".length());
                        String role = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
                        return SliceIdentifier.of(module.isEmpty() ? contextName : module, role);
                    }

                    @Override
                    public String getDescription() {
                        return "domain subpackages of each module";
                    }
                })
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true)
                .as(describe("BC-13", "同一模組內的 domain 子套件（aggregate、entity、valueobject…）不得形成循環依賴"));
    }

    ArchRule modulesAreFreeOfCycles() {
        return slices().assignedFrom(new SliceAssignment() {
                    @Override
                    public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                        String packageName = javaClass.getPackageName();
                        if (!packageName.startsWith(basePackage + ".")) {
                            return SliceIdentifier.ignore();
                        }
                        String relative = packageName.substring(basePackage.length() + 1);
                        String module =
                                relative.contains(".") ? relative.substring(0, relative.indexOf('.')) : relative;
                        return LAYERS.contains(module) ? SliceIdentifier.ignore() : SliceIdentifier.of(module);
                    }

                    @Override
                    public String getDescription() {
                        return "modules";
                    }
                })
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true)
                .as(describe("BC-14", "同一 context 內的模組之間不得循環依賴"));
    }

    ArchRule everyClassBelongsToALayer() {
        return classes()
                .that()
                .resideInAPackage(context)
                .should()
                .resideInAnyPackage(
                        domain,
                        application,
                        entrypoint,
                        infrastructure,
                        basePackage + "..bootstrap..",
                        // 對外公開的 *-api 模組（例如 inventory-api），只能在 context 最上層。
                        basePackage + ".api..")
                .as(describe(
                        "BC-15",
                        "每個類別都必須位於 domain、application、entrypoint、infrastructure、bootstrap 之下，"
                                + "或位於 context 最上層的 api（對外公開模組）"));
    }

    private static ArchCondition<JavaMethod> receiveExactlyOneParameter() {
        return new ArchCondition<>("receive exactly one parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || method.getModifiers().contains(JavaModifier.STATIC)) {
                    return;
                }
                List<JavaClass> parameters = method.getRawParameterTypes();
                if (parameters.size() != 1) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " receives " + parameters.size()
                                    + " parameters; exactly one complete Application Event is required"));
                }
            }
        };
    }
}
