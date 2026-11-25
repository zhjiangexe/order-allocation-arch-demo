package com.flowzati.archone.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Application layer rules shared by every bounded context: which roles the application packages hold, the shape of a
 * Usecase operation, and how Usecase／Interactor／Service／Coordinator may depend on each other.
 *
 * <p>Every rule's identifier, rationale and fix are documented in this module's {@code README.md}.
 */
final class BoundedContextApplicationRules {

    static final Set<String> APPLICATION_ROLES =
            Set.of("enums", "event", "invocation", "policy", "port", "result", "service", "state", "store", "usecase");

    /** 查詢型 Usecase 的名稱開頭；它們可以直接接收識別碼，但不得寫入或發布。 */
    static final List<String> QUERY_PREFIXES = List.of("Get", "List");

    /** 查詢型 Usecase 只能呼叫以這些動詞開頭的 store method。 */
    static final List<String> READ_VERBS = List.of("find", "get", "list", "count", "exists", "load", "search");

    private final String basePackage;
    private final String contextName;
    private final String application;
    private final String usecase;
    private final String service;
    private final String store;

    BoundedContextApplicationRules(String basePackage) {
        this.basePackage = basePackage;
        contextName = basePackage.substring(basePackage.lastIndexOf('.') + 1);
        application = basePackage + "..application..";
        usecase = layerRole("application", "usecase");
        service = layerRole("application", "service");
        store = layerRole("application", "store");
    }

    private String layerRole(String layer, String role) {
        return basePackage + ".." + layer + "." + role + "..";
    }

    private String describe(String id, String text) {
        return "[" + id + "] " + contextName + "｜" + text;
    }

    ArchRule applicationClassesUseKnownRolePackages() {
        return classes()
                .that()
                .resideInAPackage(application)
                .should()
                .resideInAnyPackage(APPLICATION_ROLES.stream()
                        .map(role -> layerRole("application", role))
                        .toArray(String[]::new))
                .allowEmptyShould(true)
                .as(describe(
                        "BC-36",
                        "Application 類別只能放在 enums、event、invocation、policy、port、result、service、state、store、"
                                + "usecase 之一（不得直接放在 application 根）"));
    }

    ArchRule usecasePackageHoldsUsecasesAndInteractors() {
        ArchRule packageHoldsOnlyUsecases = classes()
                .that()
                .resideInAPackage(usecase)
                .and()
                .areTopLevelClasses()
                .should()
                .haveNameMatching(".*(Usecase|Interactor)$")
                .allowEmptyShould(true);
        ArchRule usecasesLiveInPackage = classes()
                .that()
                .resideInAPackage(application)
                .and()
                .areTopLevelClasses()
                .and()
                .haveNameMatching(".*(Usecase|Interactor)$")
                .should()
                .resideInAPackage(usecase)
                .allowEmptyShould(true);
        return CompositeArchRule.of(packageHoldsOnlyUsecases)
                .and(usecasesLiveInPackage)
                .as(describe("BC-37", "application.usecase 只放 *Usecase 與 *Interactor；這兩種類別也只能放在這裡"));
    }

    ArchRule usecaseOperationsReceiveOneCommand() {
        return classes()
                .that(areUsecasesOrInteractors())
                .should(haveOneOperationShapedByItsKind())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-38",
                        "*Usecase／*Interactor 只有一個 public operation；會改變狀態的只能接收一個 application.invocation 的 "
                                + "*Command（排程觸發的批次可以不收參數）；名稱以 Get／List 開頭的查詢可以直接接收識別碼"));
    }

    ArchRule queryUsecasesAreReadOnly() {
        return classes()
                .that(areUsecasesOrInteractors())
                .and(areQueries())
                .should(onlyReadState())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-39",
                        "Get／List 查詢 Usecase 只能讀：不得依賴 Publisher 或 Application Event，呼叫 store 時只能用 "
                                + String.join("／", READ_VERBS) + " 開頭的 method"));
    }

    ArchRule parameterlessOperationsAreTriggeredBySchedulers() {
        return classes()
                .that(areUsecasesOrInteractors())
                .and(DescribedPredicate.not(areQueries()))
                .should(beCalledOnlyFromScheduleWhenParameterless())
                .allowEmptyShould(true)
                .as(describe("BC-40", "不收參數的 Usecase／Interactor operation 只能由 entrypoint.schedule 呼叫"));
    }

    ArchRule interactorsComposeUsecases() {
        ArchRule interactorsUseUsecases = classes()
                .that()
                .resideInAPackage(usecase)
                .and()
                .haveSimpleNameEndingWith("Interactor")
                .should()
                .dependOnClassesThat(resideInAPackage(usecase)
                        .and(simpleNameEndingWith("Usecase").or(simpleNameEndingWith("Interactor"))))
                .allowEmptyShould(true);
        ArchRule usecasesDoNotUseInteractors = noClasses()
                .that()
                .resideInAPackage(usecase)
                .and()
                .haveSimpleNameEndingWith("Usecase")
                .should()
                .dependOnClassesThat(resideInAPackage(usecase).and(simpleNameEndingWith("Interactor")))
                .allowEmptyShould(true);
        return CompositeArchRule.of(interactorsUseUsecases)
                .and(usecasesDoNotUseInteractors)
                .as(describe("BC-41", "*Interactor 必須組合至少一個 Usecase 或 Interactor；*Usecase 不得反向依賴 Interactor"));
    }

    ArchRule servicePackageHoldsServicesAndCoordinators() {
        ArchRule packageHoldsOnlyServices = classes()
                .that()
                .resideInAPackage(service)
                .and()
                .areTopLevelClasses()
                .should()
                .haveNameMatching(".*(Service|Coordinator)$")
                .allowEmptyShould(true);
        ArchRule servicesLiveInPackage = classes()
                .that()
                .resideInAPackage(application)
                .and()
                .areTopLevelClasses()
                .and()
                .haveNameMatching(".*(Service|Coordinator)$")
                .should()
                .resideInAPackage(service)
                .allowEmptyShould(true);
        return CompositeArchRule.of(packageHoldsOnlyServices)
                .and(servicesLiveInPackage)
                .as(describe("BC-42", "application.service 只放 *Service 與 *Coordinator；這兩種類別也只能放在這裡"));
    }

    ArchRule servicesStayBelowUsecases() {
        ArchRule coordinatorsUseServices = classes()
                .that()
                .resideInAPackage(service)
                .and()
                .haveSimpleNameEndingWith("Coordinator")
                .should()
                .dependOnClassesThat(resideInAPackage(service)
                        .and(simpleNameEndingWith("Service").or(simpleNameEndingWith("Coordinator"))))
                .allowEmptyShould(true);
        ArchRule servicesDoNotUseCoordinators = noClasses()
                .that()
                .resideInAPackage(service)
                .and()
                .haveSimpleNameEndingWith("Service")
                .should()
                .dependOnClassesThat(resideInAPackage(service).and(simpleNameEndingWith("Coordinator")))
                .allowEmptyShould(true);
        ArchRule servicesDoNotUseUsecases = noClasses()
                .that()
                .resideInAPackage(service)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(usecase)
                .allowEmptyShould(true);
        return CompositeArchRule.of(coordinatorsUseServices)
                .and(servicesDoNotUseCoordinators)
                .and(servicesDoNotUseUsecases)
                .as(describe(
                        "BC-43",
                        "*Coordinator 必須組合至少一個 Service 或 Coordinator；*Service 不得依賴 Coordinator；"
                                + "application.service 不得依賴 usecase"));
    }

    ArchRule interactorsOnlyRead() {
        return classes()
                .that(areUsecasesOrInteractors())
                .and()
                .haveSimpleNameEndingWith("Interactor")
                .should(onlyReadState())
                .allowEmptyShould(true)
                .as(describe(
                        "BC-45",
                        "*Interactor 只能讀：寫入與發布一律在它呼叫的 Usecase 裡；呼叫 store 時只能用 " + String.join("／", READ_VERBS)
                                + " 開頭的 method"));
    }

    ArchRule entrypointsEnterThroughUsecases() {
        return noClasses()
                .that()
                .resideInAPackage(basePackage + "..entrypoint..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(service, store)
                .allowEmptyShould(true)
                .as(
                        describe(
                                "BC-44",
                                "Entrypoint 只能透過 Usecase／Interactor 進入 application；不得直接使用 application.service 或 application.store"));
    }

    // -------------------------------------------------------------- helpers

    private DescribedPredicate<JavaClass> areUsecasesOrInteractors() {
        return DescribedPredicate.describe(
                "are top-level *Usecase or *Interactor classes in application.usecase",
                javaClass -> javaClass.isTopLevelClass()
                        && javaClass.getPackageName().startsWith(basePackage + ".")
                        && javaClass.getPackageName().endsWith(".application.usecase")
                        && (javaClass.getSimpleName().endsWith("Usecase")
                                || javaClass.getSimpleName().endsWith("Interactor")));
    }

    private static DescribedPredicate<JavaClass> areQueries() {
        return DescribedPredicate.describe(
                "are named Get* or List*",
                javaClass -> QUERY_PREFIXES.stream().anyMatch(javaClass.getSimpleName()::startsWith));
    }

    private static boolean isQuery(JavaClass javaClass) {
        return areQueries().test(javaClass);
    }

    private static List<JavaMethod> publicOperations(JavaClass javaClass) {
        return javaClass.getMethods().stream()
                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
                .filter(method -> !method.getModifiers().contains(JavaModifier.BRIDGE))
                .toList();
    }

    private static Optional<JavaMethod> singleOperation(JavaClass javaClass) {
        List<JavaMethod> operations = publicOperations(javaClass);
        return operations.size() == 1 ? Optional.of(operations.getFirst()) : Optional.empty();
    }

    private ArchCondition<JavaClass> haveOneOperationShapedByItsKind() {
        return new ArchCondition<>("declare one public operation shaped by whether it changes state") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                List<JavaMethod> operations = publicOperations(javaClass);
                if (operations.size() != 1) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " declares " + operations.size()
                                    + " public operations; exactly one is required"));
                    return;
                }
                JavaMethod operation = operations.getFirst();
                if (operation.getModifiers().contains(JavaModifier.STATIC)) {
                    events.add(
                            SimpleConditionEvent.violated(operation, operation.getFullName() + " must not be static"));
                }
                if (isQuery(javaClass)) {
                    return;
                }
                List<JavaClass> parameters = operation.getRawParameterTypes();
                if (parameters.isEmpty()) {
                    return;
                }
                if (parameters.size() == 1 && isCommand(parameters.getFirst())) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        operation,
                        operation.getFullName() + " has parameters "
                                + parameters.stream().map(JavaClass::getName).toList()
                                + "; a state-changing operation receives exactly one application.invocation *Command"));
            }
        };
    }

    private boolean isCommand(JavaClass type) {
        return type.getPackageName().startsWith(basePackage + ".")
                && type.getPackageName().endsWith(".application.invocation")
                && type.getSimpleName().endsWith("Command");
    }

    private ArchCondition<JavaClass> onlyReadState() {
        return new ArchCondition<>("only read state") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                javaClass.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass().getBaseComponentType())
                        .filter(target -> isInRole(target, "event")
                                || (isInRole(target, "port")
                                        && target.getSimpleName().endsWith("Publisher")))
                        .map(JavaClass::getName)
                        .distinct()
                        .forEach(target -> events.add(SimpleConditionEvent.violated(
                                javaClass,
                                javaClass.getName() + " depends on " + target + "; only a Usecase may publish")));
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    String name = call.getName();
                    if (isInRole(owner, "store") && READ_VERBS.stream().noneMatch(name::startsWith)) {
                        events.add(SimpleConditionEvent.violated(
                                call,
                                javaClass.getName() + " calls " + owner.getSimpleName() + "." + name
                                        + "; only a Usecase may write, others may only call store methods starting with "
                                        + READ_VERBS));
                    }
                }
            }
        };
    }

    private boolean isInRole(JavaClass type, String role) {
        return type.getPackageName().startsWith(basePackage + ".")
                && type.getPackageName().endsWith(".application." + role);
    }

    private ArchCondition<JavaClass> beCalledOnlyFromScheduleWhenParameterless() {
        return new ArchCondition<>("be called only from entrypoint.schedule when its operation takes no parameters") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Optional<JavaMethod> operation = singleOperation(javaClass)
                        .filter(method -> method.getRawParameterTypes().isEmpty());
                if (operation.isEmpty()) {
                    return;
                }
                operation.get().getCallsOfSelf().stream()
                        .filter(call -> !isInSchedule(call.getOriginOwner()))
                        .forEach(call -> events.add(SimpleConditionEvent.violated(
                                call,
                                call.getOriginOwner().getName() + " calls parameterless "
                                        + operation.get().getFullName()
                                        + "; only entrypoint.schedule may trigger it")));
            }
        };
    }

    private boolean isInSchedule(JavaClass origin) {
        return origin.getPackageName().startsWith(basePackage + ".")
                && (origin.getPackageName().endsWith(".entrypoint.schedule")
                        || origin.getPackageName().contains(".entrypoint.schedule."));
    }
}
