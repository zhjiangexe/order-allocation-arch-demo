package com.flowzati.archone.messaging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PureMessagingModuleArchitectureTest {

  private static final List<String> PURE_MODULES = List.of(
      "messaging-api",
      "messaging-events",
      "messaging-producer-common",
      "messaging-consumer-common",
      "messaging-jdbc-common",
      "messaging-producer-jdbc",
      "messaging-consumer-jdbc",
      "messaging-consumer-kafka",
      "messaging-test-support");
  private static final List<String> FORBIDDEN_IMPORTS = List.of(
      "import org.springframework.",
      "import io.micrometer.",
      "import jakarta.persistence.",
      "import javax.persistence.");

  @Test
  void pureProductionSourcesDoNotImportSpringOrJpa() throws IOException {
    List<String> violations = new ArrayList<>();
    for (String module : PURE_MODULES) {
      Path sourceRoot = root().resolve("messaging").resolve(module).resolve("src/main/java");
      if (!Files.exists(sourceRoot)) {
        continue;
      }
      try (var sources = Files.walk(sourceRoot)) {
        for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
          String content = Files.readString(source);
          FORBIDDEN_IMPORTS.stream()
              .filter(content::contains)
              .map(forbidden -> module + ": " + source.getFileName() + " -> " + forbidden)
              .forEach(violations::add);
        }
      }
    }

    assertThat(violations).as("pure messaging source boundary violations").isEmpty();
  }

  @Test
  void kafkaMapperModuleDependsOnlyOnGenericApiAndKafkaClient() throws IOException {
    Path module = root().resolve("messaging/messaging-consumer-kafka");
    String build = Files.readString(module.resolve("build.gradle"));
    String productionSources = readProductionSources(module.resolve("src/main/java"));

    assertThat(build)
        .contains("project(':messaging:messaging-api')")
        .contains("libs.kafka.clients")
        .doesNotContain("messaging-events")
        .doesNotContain("messaging-consumer-common")
        .doesNotContain("starter.kafka");
    assertThat(productionSources)
        .doesNotContain("com.flowzati.archone.messaging.events")
        .doesNotContain("org.springframework.kafka")
        .doesNotContain("@KafkaListener");
  }

  @Test
  void springKafkaRuntimeOwnsProgrammaticContainersWithoutOwningTypedEvents()
      throws IOException {
    Path module = root().resolve("messaging/messaging-spring-consumer-kafka");
    String build = Files.readString(module.resolve("build.gradle"));
    String productionSources = readProductionSources(module.resolve("src/main/java"));

    assertThat(build)
        .contains("project(':messaging:messaging-consumer-common')")
        .contains("project(':messaging:messaging-consumer-kafka')")
        .contains("project(':messaging:messaging-spring-observability')")
        .contains("libs.spring.kafka")
        .doesNotContain("messaging-events")
        .doesNotContain("id 'org.springframework.boot'")
        .doesNotContain("    implementation libs.spring.boot");
    assertThat(productionSources)
        .contains("implements MessageConsumerImplementation")
        .contains("ConcurrentKafkaListenerContainerFactory")
        .contains("policy.observationEnabledOverride()")
        .contains("policy.autoStartupOverride().orElse(container.isAutoStartup())")
        .contains("errorHandlerFactory.create(subscription)")
        .contains("implements RetryListener")
        .doesNotContain("com.flowzati.archone.messaging.events")
        .doesNotContain("@KafkaListener");
  }

  @Test
  void legacyDispatcherAndJpaFacadeArtifactsAreAbsent() {
    assertThat(root().resolve(
        "messaging/messaging-events/src/main/java/com/flowzati/archone/messaging/events/"
            + "IntegrationEventHandler.java")).doesNotExist();
    assertThat(root().resolve(
        "messaging/messaging-spring-boot-autoconfigure/src/main/java/"
            + "com/flowzati/archone/messaging/kafka/KafkaIntegrationEventDispatcher.java"))
        .doesNotExist();
    assertThat(root().resolve("messaging/messaging-producer-outbox/build.gradle")).doesNotExist();
    assertThat(root().resolve("messaging/messaging-consumer-inbox/build.gradle")).doesNotExist();
  }

  @Test
  void applicationDeclaresTramShapedSubscriptionsWithoutRecreatingKafkaMechanics()
      throws IOException {
    Path application = root().resolve("order-promising/src/main/java/com/flowzati/archone");
    Path bootstrapMessaging = application.resolve("bootstrap/messaging");
    String orderingMessaging = readProductionSources(
        application.resolve("ordering/entrypoint/messaging"));
    String allocationMessaging = readProductionSources(
        application.resolve("stock/entrypoint/messaging"));
    String failurePolicy = Files.readString(bootstrapMessaging.resolve(
        "consumer/OrderPromisingKafkaConsumerConfiguration.java"));

    assertThat(bootstrapMessaging.resolve(
        "OrderPromisingIntegrationEventPreparationConfiguration.java")).doesNotExist();
    assertThat(bootstrapMessaging.resolve("IntegrationEventSubscriptionTopology.java"))
        .doesNotExist();
    assertThat(orderingMessaging + allocationMessaging)
        .contains("IntegrationEventHandlersBuilder")
        .contains("factory.make(")
        .contains("@ConditionalOnIntegrationEventConsumption")
        .doesNotContain("@KafkaListener");
    assertThat(failurePolicy)
        .contains("KafkaConsumerFailurePolicyResolver")
        .contains("OptimisticLockingRetryExhaustedException")
        .doesNotContain("KafkaOperations")
        .doesNotContain("CommonErrorHandler")
        .doesNotContain("ObservationRegistry")
        .doesNotContain("ResolvedMessageSubscription");
  }

  @Test
  void optimisticLockingIsAnOptionalGenericSpringCapability() throws IOException {
    Path root = root();
    Path module = root.resolve("messaging/messaging-spring-optimistic-locking");
    String build = Files.readString(module.resolve("build.gradle"));
    String productionSources = readProductionSources(module.resolve("src/main/java"));
    String applicationBuild = Files.readString(root.resolve("order-promising/build.gradle"));
    String applicationMessaging = readProductionSources(root.resolve(
        "order-promising/src/main/java/com/flowzati/archone"));

    assertThat(build)
        .contains("project(':messaging:messaging-consumer-common')")
        .contains("libs.spring.context")
        .contains("libs.spring.tx")
        .doesNotContain("messaging-events")
        .doesNotContain("spring.kafka")
        .doesNotContain("contracts");
    assertThat(productionSources)
        .contains("class OptimisticLockingDecorator")
        .contains("class OptimisticLockingDecoratorConfiguration")
        .contains("OptimisticLockingFailureException")
        .doesNotContain("Allocation")
        .doesNotContain("Kafka")
        .doesNotContain("com.flowzati.archone.contracts");
    assertThat(applicationBuild)
        .contains("messaging-spring-optimistic-locking");
    assertThat(applicationMessaging)
        .contains("@Import(OptimisticLockingDecoratorConfiguration.class)")
        .contains("AllocationOptimisticLockRetryObserver")
        .doesNotContain("AllocationOptimisticLockRetryDecorator");
  }

  @Test
  void applicationKeepsInboundConsumersAndOutboundPublishersOnOppositeAdapters()
      throws IOException {
    Path application = root().resolve("order-promising/src/main/java/com/flowzati/archone");
    String orderingConsumers = readProductionSources(
        application.resolve("ordering/entrypoint/messaging"));
    String allocationConsumers = readProductionSources(
        application.resolve("stock/entrypoint/messaging"));
    String orderingProducers = readProductionSources(
        application.resolve("ordering/infrastructure/messaging/producer"));
    String allocationProducers = readProductionSources(
        application.resolve("stock/infrastructure/messaging/producer"));

    assertThat(orderingConsumers + allocationConsumers)
        .contains("EventConsumer")
        .contains("IntegrationEventHandlersBuilder")
        .doesNotContain("@Qualifier")
        .doesNotContain("OutboxAppender");
    assertThat(orderingProducers + allocationProducers)
        .contains("IntegrationEventPublisher")
        .contains("PublicationTarget")
        .doesNotContain("OutboxAppender")
        .doesNotContain("IntegrationEventHandlersBuilder");
    assertThat(application.resolve(
        "ordering/application/event/translator/OrderingDomainEventTranslator.java"))
        .doesNotExist();
    assertThat(application.resolve(
        "stock/application/event/translator/AllocationDomainEventTranslator.java"))
        .doesNotExist();
  }

  @Test
  void commonOrchestrationDoesNotContainPersistenceModels() throws IOException {
    String producer = readProductionSources(root().resolve(
        "messaging/messaging-producer-common/src/main/java"));
    String consumer = readProductionSources(root().resolve(
        "messaging/messaging-consumer-common/src/main/java"));

    assertThat(producer)
        .doesNotContain("com.flowzati.archone.messaging.outbox")
        .doesNotContain("java.sql.");
    assertThat(consumer)
        .doesNotContain("com.flowzati.archone.messaging.inbox.infrastructure")
        .doesNotContain("java.sql.");
  }

  @Test
  void observabilityArtifactsKeepCommonProducerAndConsumerDependenciesDirectional()
      throws IOException {
    String common = Files.readString(root().resolve(
        "messaging/messaging-spring-observability/build.gradle"));
    String producer = Files.readString(root().resolve(
        "messaging/messaging-spring-producer-observability/build.gradle"));
    String consumer = Files.readString(root().resolve(
        "messaging/messaging-spring-consumer-observability/build.gradle"));

    assertThat(common)
        .contains("project(':messaging:messaging-api')")
        .contains("libs.micrometer.observation")
        .doesNotContain("messaging-producer-common")
        .doesNotContain("messaging-consumer-common")
        .doesNotContain("spring.kafka");
    assertThat(producer)
        .contains("project(':messaging:messaging-spring-observability')")
        .doesNotContain("messaging-consumer")
        .doesNotContain("messaging-producer-jdbc")
        .doesNotContain("spring.kafka");
    assertThat(consumer)
        .contains("project(':messaging:messaging-consumer-common')")
        .contains("project(':messaging:messaging-spring-observability')")
        .doesNotContain("messaging-producer")
        .doesNotContain("messaging-producer-outbox")
        .doesNotContain("spring.kafka");
  }

  @Test
  void narrowStartersAndOptionalAutoConfigurationDoNotLeakTheOppositeRuntime()
      throws IOException {
    String autoConfiguration = Files.readString(root().resolve(
        "messaging/messaging-spring-boot-autoconfigure/build.gradle"));
    String producerStarter = Files.readString(root().resolve(
        "messaging/messaging-spring-producer-starter/build.gradle"));
    String consumerStarter = Files.readString(root().resolve(
        "messaging/messaging-spring-consumer-starter/build.gradle"));
    String allInOneStarter = Files.readString(root().resolve(
        "messaging/messaging-spring-boot-starter/build.gradle"));

    assertThat(autoConfiguration)
        .contains("compileOnly project(':messaging:messaging-spring-producer-jdbc')")
        .contains("compileOnly project(':messaging:messaging-spring-consumer-kafka')")
        .doesNotContain("    api project(")
        .doesNotContain("    implementation project(");
    assertThat(producerStarter)
        .contains("messaging-producer-common")
        .contains("messaging-spring-producer-jdbc")
        .contains("messaging-spring-producer-observability")
        .doesNotContain("messaging-consumer")
        .doesNotContain("starter.kafka");
    assertThat(consumerStarter)
        .contains("messaging-consumer-common")
        .contains("messaging-spring-consumer-kafka")
        .contains("messaging-spring-consumer-observability")
        .doesNotContain("messaging-spring-optimistic-locking")
        .doesNotContain("messaging-producer")
        .doesNotContain("messaging-producer-outbox");
    assertThat(allInOneStarter)
        .contains("messaging-spring-producer-starter")
        .contains("messaging-spring-consumer-starter")
        .doesNotContain("messaging-producer-outbox")
        .doesNotContain("messaging-consumer-inbox")
        .doesNotContain("starter.data.jpa");
  }

  @Test
  void consumerCommonOwnsTheOnlyRuntimeSpiAndResolvesChannelMappingBeforeTransport()
      throws IOException {
    Path consumerCommon = root().resolve("messaging/messaging-consumer-common/src/main/java");
    String commonSources = readProductionSources(consumerCommon);
    String kafkaSources = readProductionSources(root().resolve(
        "messaging/messaging-consumer-kafka/src/main/java"));
    List<Path> runtimeSpiDefinitions;
    try (var sources = Files.walk(root().resolve("messaging"))) {
      runtimeSpiDefinitions = sources
          .filter(path -> path.getFileName().toString()
              .equals("MessageConsumerImplementation.java"))
          .toList();
    }

    assertThat(runtimeSpiDefinitions).containsExactly(consumerCommon.resolve(
        "com/flowzati/archone/messaging/consumer/common/MessageConsumerImplementation.java"));
    assertThat(commonSources)
        .contains("implements MessageConsumer")
        .contains("channelMapping.transform(logicalChannel)")
        .contains("MessageConsumerImplementation implementation");
    assertThat(kafkaSources)
        .doesNotContain("MessageConsumerImplementation")
        .doesNotContain("ChannelMapping")
        .doesNotContain("interface KafkaMessageConsumer");
  }

  private String readProductionSources(Path sourceRoot) throws IOException {
    StringBuilder content = new StringBuilder();
    try (var sources = Files.walk(sourceRoot)) {
      for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        content.append(Files.readString(source)).append('\n');
      }
    }
    return content.toString();
  }

  private Path root() {
    return Path.of(System.getProperty("archone.rootDir"));
  }
}
