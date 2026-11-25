package com.flowzati.archone.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Package role and naming rules shared by every bounded context: which kinds of classes each package may hold and how
 * they are named. Layer dependency and Integration Event rules live in {@link BoundedContextArchitectureRules}.
 *
 * <p>Every rule's identifier, rationale and fix are documented in this module's {@code README.md}.
 */
final class BoundedContextRoleRules {

    static final Set<String> DOMAIN_ROLES = Set.of("aggregate", "entity", "valueobject", "enums", "service", "policy");
    static final Set<String> ENTRYPOINT_CHANNELS = Set.of("rest", "messaging", "temporal", "schedule", "event");

    private static final String JPA_ENTITY = "jakarta.persistence.Entity";
    private static final String JPA_EMBEDDABLE = "jakarta.persistence.Embeddable";
    private static final String JPA_EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    private static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";
    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String SPRING_EVENT_LISTENER = "org.springframework.context.event.EventListener";
    private static final String SPRING_APPLICATION_EVENT_PUBLISHER =
            "org.springframework.context.ApplicationEventPublisher";
    private static final Set<String> REST_RETURN_WRAPPERS = Set.of(
            "void",
            "java.lang.Void",
            "org.springframework.http.ResponseEntity",
            "java.util.List",
            "java.util.Set",
            "java.util.Optional");

    private final String basePackage;
    private final String contextName;
    private final String context;
    private final String domain;
    private final String application;
    private final String entrypoint;

    BoundedContextRoleRules(String basePackage) {
        this.basePackage = basePackage;
        contextName = basePackage.substring(basePackage.lastIndexOf('.') + 1);
        context = basePackage + "..";
        domain = basePackage + "..domain..";
        application = basePackage + "..application..";
        entrypoint = basePackage + "..entrypoint..";
    }

    private String layerRole(String layer, String role) {
        return basePackage + ".." + layer + "." + role + "..";
    }

    private String describe(String id, String text) {
        return "[" + id + "] " + contextName + "｜" + text;
    }

    // ---------------------------------------------------------------- domain

    ArchRule domainClassesUseKnownRolePackages() {
        return classes()
                .that()
                .resideInAPackage(domain)
                .should()
                .resideInAnyPackage(DOMAIN_ROLES.stream()
                        .map(role -> layerRole("domain", role))
                        .toArray(String[]::new))
                .allowEmptyShould(true)
                .as(describe(
                        "BC-16",
                        "Domain 類別只能放在 aggregate、entity、valueobject、enums、service、policy 之一"
                                + "（不得有 type、error、exception 等其他子套件，也不得直接放在 domain 根）"));
    }

    ArchRule enumsLiveInEnumsPackages() {
        ArchRule enumsInEnumsPackage = classes()
                .that()
                .resideInAnyPackage(domain, application)
                .and()
                .areEnums()
                .should()
                .resideInAnyPackage(layerRole("domain", "enums"), layerRole("application", "enums"))
                .andShould()
                .beTopLevelClasses()
                .allowEmptyShould(true);
        ArchRule enumsPackageHoldsOnlyEnums = classes()
                .that()
                .resideInAnyPackage(layerRole("domain", "enums"), layerRole("application", "enums"))
                .should()
                .beEnums()
                .allowEmptyShould(true);
        return CompositeArchRule.of(enumsInEnumsPackage)
                .and(enumsPackageHoldsOnlyEnums)
                .as(describe("BC-17", "Domain／application 的 enum 必須是獨立檔案，放在同層的 enums 套件；enums 套件只能放 enum"));
    }

    ArchRule valueObjectsAreImmutableValues() {
        return classes()
                .that()
                .resideInAPackage(layerRole("domain", "valueobject"))
                .and()
                .areTopLevelClasses()
                .should(beImmutableValues())
                .allowEmptyShould(true)
                .as(describe("BC-18", "domain.valueobject 只能放 record，或 final class＋全部欄位 final＋覆寫 equals 與 hashCode"));
    }

    ArchRule domainServicePackageIsFlat() {
        return classes()
                .that()
                .resideInAPackage(layerRole("domain", "service"))
                .should()
                .haveNameMatching(".*\\.domain\\.service\\.[^.]+$")
                .allowEmptyShould(true)
                .as(describe("BC-19", "domain.service 不得有子套件；介面與實作放在同一個資料夾"));
    }

    ArchRule noImplSuffix() {
        return noClasses()
                .that()
                .resideInAPackage(context)
                .should()
                .haveSimpleNameEndingWith("Impl")
                .allowEmptyShould(true)
                .as(describe("BC-20", "類別名稱不得以 Impl 結尾；實作名稱要說明它的做法或技術"));
    }

    ArchRule domainServiceImplementationsAreNamedAndImmutable() {
        return classes()
                .that()
                .resideInAPackage(layerRole("domain", "service"))
                .and(implementDomainServiceInterface())
                .should(beNamedAfterInterfaceWithFinalFields())
                .allowEmptyShould(true)
                .as(describe("BC-21", "domain.service 的實作名稱＝做法描述＋介面名稱（例如 PriorityCapacityWavePlanner），且欄位全部 final"));
    }

    ArchRule domainServiceImplementationsAreReferencedOnlyFromConfiguration() {
        return noClasses()
                .that()
                .resideInAPackage(context)
                .and()
                .resideOutsideOfPackage(layerRole("infrastructure", "configuration"))
                .and(DescribedPredicate.not(implementDomainServiceInterface()))
                .should()
                .dependOnClassesThat(implementDomainServiceInterface())
                .allowEmptyShould(true)
                .as(describe("BC-22", "domain.service 的實作只能由 infrastructure.configuration 建立；其他程式只能依賴介面"));
    }

    ArchRule domainServiceInterfacesHaveImplementations() {
        return classes()
                .that()
                .resideInAPackage(layerRole("domain", "service"))
                .and()
                .areInterfaces()
                .should(haveAnImplementation())
                .allowEmptyShould(true)
                .as(describe("BC-23", "domain.service 的每個介面都必須至少有一個實作"));
    }

    // ----------------------------------------------------------- application

    ArchRule applicationStoresArePureInterfaces() {
        return classes()
                .that()
                .resideInAPackage(layerRole("application", "store"))
                .and()
                .areTopLevelClasses()
                .should()
                .beInterfaces()
                .andShould()
                .haveSimpleNameEndingWith("Store")
                .allowEmptyShould(true)
                .as(describe("BC-24", "application.store 只能放以 Store 結尾的純介面"));
    }

    ArchRule invocationsAreCommandsQueriesOrTheirComponents() {
        return classes()
                .that()
                .resideInAPackage(layerRole("application", "invocation"))
                .and()
                .areTopLevelClasses()
                .should(beCommandQueryOrComponentRecord())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-25",
                        "application.invocation 只能放 *Command／*Query record，或被某個 Command／Query 欄位使用的組成 record"));
    }

    // ------------------------------------------------------------ entrypoint

    ArchRule entrypointsUseKnownChannelPackages() {
        return classes()
                .that()
                .resideInAPackage(entrypoint)
                .should()
                .resideInAnyPackage(ENTRYPOINT_CHANNELS.stream()
                        .map(channel -> layerRole("entrypoint", channel))
                        .toArray(String[]::new))
                .allowEmptyShould(true)
                .as(describe(
                        "BC-26",
                        "Entrypoint 類別只能放在依觸發來源命名的 rest、messaging、temporal、schedule、event 之一（不得直接放在 entrypoint 根）"));
    }

    ArchRule restPackagesSeparateControllersRequestsAndResponses() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "rest"))
                .and()
                .areTopLevelClasses()
                .should(followRestPackageRoles())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-27",
                        "entrypoint.rest 根只放 @RestController *Rest；rest.request 只放 *Request record；"
                                + "rest.response 只放 *Response record"));
    }

    ArchRule restOperationsReturnResponses() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "rest"))
                .and()
                .areAnnotatedWith(REST_CONTROLLER)
                .should(returnOnlyResponsesOrPublishedApiTypes())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-46",
                        "@RestController 的 public method 只能回傳 rest.response 的 *Response、context 對外 api 套件的型別，"
                                + "或沒有內容（void／ResponseEntity<Void>）；可以包在 ResponseEntity、List、Set、Optional 裡"));
    }

    ArchRule messagingEntrypointsAreConsumersAndOneSubscriptionsHolder() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "messaging"))
                .and()
                .areTopLevelClasses()
                .should(beConsumerOrSingleSubscriptionsPerModule())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-28",
                        "entrypoint.messaging 只放 *EventConsumer 與 *EventSubscriptions；每個模組只能有一個 "
                                + "*EventSubscriptions，且只能宣告 static final String 常數"));
    }

    ArchRule scheduleEntrypointsAreSchedulers() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "schedule"))
                .and()
                .areTopLevelClasses()
                .should()
                .haveSimpleNameEndingWith("Scheduler")
                .allowEmptyShould(true)
                .as(describe("BC-29", "entrypoint.schedule 只放 *Scheduler"));
    }

    ArchRule temporalEntrypointsAreAdapters() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "temporal"))
                .and()
                .areTopLevelClasses()
                .should()
                .haveSimpleNameEndingWith("Adapter")
                .allowEmptyShould(true)
                .as(describe("BC-30", "entrypoint.temporal 只放 *Adapter"));
    }

    ArchRule eventEntrypointsAreListeners() {
        return classes()
                .that()
                .resideInAPackage(layerRole("entrypoint", "event"))
                .and()
                .areTopLevelClasses()
                .should()
                .haveSimpleNameEndingWith("EventListener")
                .allowEmptyShould(true)
                .as(describe("BC-47", "entrypoint.event 只放 *EventListener（同一 context 內的 Application Event 入口）"));
    }

    ArchRule applicationEventAdaptersImplementPublisherPorts() {
        return classes()
                .that()
                .resideInAPackage(layerRole("infrastructure", "event"))
                .and()
                .areTopLevelClasses()
                .should(beApplicationEventAdapterImplementingPublisherPort())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-48",
                        "infrastructure.event 只放 *ApplicationEventAdapter，且必須實作 application.port 的 *Publisher"));
    }

    ArchRule applicationEventsAreHandledOnlyInEventEntrypoints() {
        return methods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAPackage(basePackage + "..")
                .and()
                .areMetaAnnotatedWith(SPRING_EVENT_LISTENER)
                .should()
                .beDeclaredInClassesThat()
                .resideInAPackage(layerRole("entrypoint", "event"))
                .allowEmptyShould(true)
                .as(describe("BC-49", "只有 entrypoint.event 可以接收 Spring Application Event（@EventListener）"));
    }

    ArchRule applicationEventsArePublishedOnlyByEventAdapters() {
        return noClasses()
                .that()
                .resideInAPackage(basePackage + "..")
                .and()
                .resideOutsideOfPackage(layerRole("infrastructure", "event"))
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(SPRING_APPLICATION_EVENT_PUBLISHER)
                .allowEmptyShould(true)
                .as(describe("BC-50", "只有 infrastructure.event 可以使用 Spring ApplicationEventPublisher 發布事件"));
    }

    // -------------------------------------------------------- infrastructure

    ArchRule jpaModelsAreEntitiesOrPrimaryKeys() {
        ArchRule modelPackageRoles = classes()
                .that()
                .resideInAPackage(persistence("jpa.model"))
                .and()
                .areTopLevelClasses()
                .should(beEntityOrEmbeddedPrimaryKey())
                .allowEmptyShould(true);
        ArchRule jpaTypesLiveInModel = classes()
                .that(resideInAPackage(context).and(annotatedWith(JPA_ENTITY).or(annotatedWith(JPA_EMBEDDABLE))))
                .should()
                .resideInAPackage(persistence("jpa.model"))
                .allowEmptyShould(true);
        return CompositeArchRule.of(modelPackageRoles)
                .and(jpaTypesLiveInModel)
                .as(describe(
                        "BC-31",
                        "@Entity 與 @Embeddable 只能放在 jpa.model；jpa.model 只放 @Entity *Entity，以及被 @EmbeddedId "
                                + "使用的 @Embeddable *PrimaryKey"));
    }

    ArchRule jpaRepositoriesExtendJpaRepository() {
        return classes()
                .that()
                .resideInAPackage(persistence("jpa.repository"))
                .and()
                .areTopLevelClasses()
                .should()
                .beInterfaces()
                .andShould()
                .haveNameMatching(".*\\.Jpa[^.]*Repository$")
                .andShould()
                .beAssignableTo(JPA_REPOSITORY)
                .allowEmptyShould(true)
                .as(describe("BC-32", "jpa.repository 只放繼承 JpaRepository 的 Jpa*Repository 介面"));
    }

    ArchRule storeAdaptersAreNamedAfterTheirStore() {
        return classes()
                .that()
                .resideInAnyPackage(persistence("jpa.store"), persistence("jdbc.store"))
                .and()
                .areTopLevelClasses()
                .should(beStoreAdapterOrPackagePrivateHelper())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-33",
                        "jpa.store／jdbc.store 的 public 類別必須是 *StoreAdapter，名稱＝所實作的 application.store 介面名稱＋"
                                + "Adapter（不加 Jpa／Jdbc 前綴）；只供它使用的輔助類別必須是 package-private"));
    }

    ArchRule persistenceUsesKnownPackages() {
        return classes()
                .that()
                .resideInAPackage(basePackage + "..infrastructure.persistence..")
                .should()
                .resideInAnyPackage(
                        persistence("jpa.model"),
                        persistence("jpa.repository"),
                        persistence("jpa.mapper"),
                        persistence("jpa.store"),
                        persistence("jdbc.store"))
                .allowEmptyShould(true)
                .as(describe(
                        "BC-34", "Persistence 類別只能放在 jpa.model、jpa.repository、jpa.mapper、jpa.store、jdbc.store 之一"));
    }

    ArchRule jpaMappersAreMappers() {
        return classes()
                .that()
                .resideInAPackage(persistence("jpa.mapper"))
                .and()
                .areTopLevelClasses()
                .should()
                .haveSimpleNameEndingWith("Mapper")
                .allowEmptyShould(true)
                .as(describe("BC-35", "jpa.mapper 只放 *Mapper"));
    }

    private String persistence(String subpackage) {
        return basePackage + "..infrastructure.persistence." + subpackage + "..";
    }

    // ------------------------------------------------------------ predicates

    private DescribedPredicate<JavaClass> implementDomainServiceInterface() {
        return new DescribedPredicate<>("implement a domain.service interface") {
            @Override
            public boolean test(JavaClass javaClass) {
                return !javaClass.isInterface()
                        && domainServiceInterfaceOf(javaClass).isPresent();
            }
        };
    }

    private Optional<JavaClass> domainServiceInterfaceOf(JavaClass javaClass) {
        return javaClass.getRawInterfaces().stream()
                .filter(candidate -> candidate.getPackageName().startsWith(basePackage + ".")
                        && candidate.getPackageName().endsWith(".domain.service"))
                .findFirst();
    }

    // ------------------------------------------------------------ conditions

    private static ArchCondition<JavaClass> beImmutableValues() {
        return new ArchCondition<>("be a record or an immutable final class with value equality") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.isRecord()) {
                    return;
                }
                List<String> problems = new ArrayList<>();
                if (!javaClass.getModifiers().contains(JavaModifier.FINAL)) {
                    problems.add("class is not final");
                }
                for (JavaField field : javaClass.getFields()) {
                    if (!field.getModifiers().contains(JavaModifier.STATIC)
                            && !field.getModifiers().contains(JavaModifier.FINAL)) {
                        problems.add("field " + field.getName() + " is not final");
                    }
                }
                if (!declaresMethod(javaClass, "equals", 1) || !declaresMethod(javaClass, "hashCode", 0)) {
                    problems.add("equals/hashCode are not overridden");
                }
                if (!problems.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + ": " + problems));
                }
            }
        };
    }

    private static boolean declaresMethod(JavaClass javaClass, String name, int parameterCount) {
        return javaClass.getMethods().stream()
                .anyMatch(method -> method.getName().equals(name)
                        && method.getRawParameterTypes().size() == parameterCount);
    }

    private ArchCondition<JavaClass> beNamedAfterInterfaceWithFinalFields() {
        return new ArchCondition<>("be named <approach><Interface> and keep every field final") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String interfaceName =
                        domainServiceInterfaceOf(javaClass).orElseThrow().getSimpleName();
                String name = javaClass.getSimpleName();
                if (!name.endsWith(interfaceName) || name.equals(interfaceName)) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " implements " + interfaceName + " but is not named <approach>"
                                    + interfaceName));
                }
                for (JavaField field : javaClass.getFields()) {
                    if (!field.getModifiers().contains(JavaModifier.FINAL)) {
                        events.add(SimpleConditionEvent.violated(
                                field, field.getFullName() + " must be final; domain services are stateless"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAnImplementation() {
        return new ArchCondition<>("have at least one implementation") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean implemented = javaClass.getAllSubclasses().stream().anyMatch(sub -> !sub.isInterface());
                if (!implemented) {
                    events.add(
                            SimpleConditionEvent.violated(javaClass, javaClass.getName() + " has no implementation"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> beCommandQueryOrComponentRecord() {
        return new ArchCondition<>("be a Command/Query record or a record used by one") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.isRecord()) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " is not a record"));
                    return;
                }
                String name = javaClass.getSimpleName();
                if (name.endsWith("Command") || name.endsWith("Query")) {
                    return;
                }
                boolean usedByInvocation = javaClass.getDirectDependenciesToSelf().stream()
                        .map(dependency -> dependency.getOriginClass())
                        .anyMatch(origin -> origin.getPackageName().equals(javaClass.getPackageName())
                                && (origin.getSimpleName().endsWith("Command")
                                        || origin.getSimpleName().endsWith("Query"))
                                && origin.getFields().stream()
                                        .anyMatch(field ->
                                                field.getAllInvolvedRawTypes().contains(javaClass)));
                if (!usedByInvocation) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " is neither a Command/Query nor a component field of one"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> followRestPackageRoles() {
        return new ArchCondition<>("follow rest / rest.request / rest.response roles") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String packageName = javaClass.getPackageName();
                String name = javaClass.getSimpleName();
                String problem = null;
                if (packageName.endsWith(".entrypoint.rest")) {
                    if (!name.endsWith("Rest") || !javaClass.isAnnotatedWith(REST_CONTROLLER)) {
                        problem = "rest root holds only @RestController *Rest";
                    }
                } else if (packageName.endsWith(".entrypoint.rest.request")) {
                    if (!name.endsWith("Request") || !javaClass.isRecord()) {
                        problem = "rest.request holds only *Request records";
                    }
                } else if (packageName.endsWith(".entrypoint.rest.response")) {
                    if (!name.endsWith("Response") || !javaClass.isRecord()) {
                        problem = "rest.response holds only *Response records";
                    }
                } else {
                    problem = "only rest, rest.request and rest.response are allowed";
                }
                if (problem != null) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + ": " + problem));
                }
            }
        };
    }

    private ArchCondition<JavaClass> beConsumerOrSingleSubscriptionsPerModule() {
        return new ArchCondition<>("be an *EventConsumer or the module's only *EventSubscriptions") {
            private final Map<String, List<String>> subscriptionsByModule = new HashMap<>();

            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String name = javaClass.getSimpleName();
                if (name.endsWith("EventConsumer")) {
                    return;
                }
                if (!name.endsWith("EventSubscriptions")) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " is neither *EventConsumer nor *EventSubscriptions"));
                    return;
                }
                subscriptionsByModule
                        .computeIfAbsent(moduleOf(javaClass), module -> new ArrayList<>())
                        .add(javaClass.getName());
                for (JavaField field : javaClass.getFields()) {
                    boolean constant = field.getModifiers().containsAll(Set.of(JavaModifier.STATIC, JavaModifier.FINAL))
                            && (field.getRawType().isEquivalentTo(String.class)
                                    || field.getRawType().isEquivalentTo(Set.class));
                    if (!constant) {
                        events.add(SimpleConditionEvent.violated(
                                field, field.getFullName() + " must be a static final String (or Set) constant"));
                    }
                }
            }

            @Override
            public void finish(ConditionEvents events) {
                subscriptionsByModule.forEach((module, holders) -> {
                    if (holders.size() > 1) {
                        events.add(SimpleConditionEvent.violated(
                                holders, "module " + module + " has several *EventSubscriptions: " + holders));
                    }
                });
                subscriptionsByModule.clear();
            }
        };
    }

    private ArchCondition<JavaClass> returnOnlyResponsesOrPublishedApiTypes() {
        return new ArchCondition<>("return only *Response, published api types, or no content") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getMethods().stream()
                        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                        .forEach(method -> method.getReturnType().getAllInvolvedRawTypes().stream()
                                .filter(type -> !isAllowedRestReturnType(type))
                                .map(JavaClass::getName)
                                .distinct()
                                .forEach(type -> events.add(SimpleConditionEvent.violated(
                                        method,
                                        method.getFullName() + " returns " + type
                                                + "; expose a rest.response *Response instead"))));
            }
        };
    }

    private boolean isAllowedRestReturnType(JavaClass type) {
        String name = type.getName();
        if (REST_RETURN_WRAPPERS.contains(name)) {
            return true;
        }
        String packageName = type.getPackageName();
        if (packageName.equals(basePackage + ".api") || packageName.startsWith(basePackage + ".api.")) {
            return true;
        }
        return packageName.startsWith(basePackage + ".")
                && packageName.endsWith(".entrypoint.rest.response")
                && type.getSimpleName().endsWith("Response");
    }

    private ArchCondition<JavaClass> beApplicationEventAdapterImplementingPublisherPort() {
        return new ArchCondition<>("be an *ApplicationEventAdapter implementing an application.port *Publisher") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.getSimpleName().endsWith("ApplicationEventAdapter")) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " is not named *ApplicationEventAdapter"));
                    return;
                }
                boolean implementsPublisherPort = javaClass.getRawInterfaces().stream()
                        .anyMatch(port -> port.getPackageName().startsWith(basePackage + ".")
                                && port.getPackageName().endsWith(".application.port")
                                && port.getSimpleName().endsWith("Publisher"));
                if (!implementsPublisherPort) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " does not implement an application.port *Publisher"));
                }
            }
        };
    }

    private String moduleOf(JavaClass javaClass) {
        String relative = javaClass.getPackageName().substring(basePackage.length() + 1);
        int entrypointIndex = relative.indexOf("entrypoint");
        return entrypointIndex <= 0 ? contextName : relative.substring(0, entrypointIndex - 1);
    }

    private static ArchCondition<JavaClass> beEntityOrEmbeddedPrimaryKey() {
        return new ArchCondition<>("be an @Entity *Entity or an @Embeddable *PrimaryKey used as @EmbeddedId") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String name = javaClass.getSimpleName();
                if (name.endsWith("Entity") && javaClass.isAnnotatedWith(JPA_ENTITY)) {
                    return;
                }
                if (name.endsWith("PrimaryKey") && javaClass.isAnnotatedWith(JPA_EMBEDDABLE)) {
                    boolean usedAsEmbeddedId = javaClass.getDirectDependenciesToSelf().stream()
                            .map(dependency -> dependency.getOriginClass())
                            .flatMap(origin -> origin.getFields().stream())
                            .anyMatch(field ->
                                    field.getRawType().equals(javaClass) && field.isAnnotatedWith(JPA_EMBEDDED_ID));
                    if (usedAsEmbeddedId) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            javaClass, javaClass.getName() + " is not used as an @EmbeddedId"));
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        javaClass,
                        javaClass.getName() + " is neither an @Entity *Entity nor an @Embeddable *PrimaryKey"));
            }
        };
    }

    private ArchCondition<JavaClass> beStoreAdapterOrPackagePrivateHelper() {
        return new ArchCondition<>("be a <Store>Adapter implementing application.store, or a package-private helper") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.getModifiers().contains(JavaModifier.PUBLIC)) {
                    return;
                }
                String name = javaClass.getSimpleName();
                boolean implementsMatchingStore = javaClass.getRawInterfaces().stream()
                        .anyMatch(store -> store.getPackageName().startsWith(basePackage + ".")
                                && store.getPackageName().endsWith(".application.store")
                                && name.equals(store.getSimpleName() + "Adapter"));
                if (!implementsMatchingStore) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName()
                                    + " must be named <application.store interface>Adapter and implement it"));
                }
            }
        };
    }
}
