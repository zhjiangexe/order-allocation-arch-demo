package com.flowzati.archone.e2e.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.messaging.observation.MessagingObservationNames;
import com.flowzati.archone.messaging.observation.MessagingObservationTags;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.testsupport.OrderFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Real infrastructure and low-level probes shared by the full-path correctness scenarios. */
final class FullPathMessagingEnvironment {

  static final String ORDER_EVENTS = OrderingEventTopics.ORDER_EVENTS;
  static final Duration NORMAL_FLOW_TIMEOUT = Duration.ofSeconds(45);
  static final Duration FAILURE_FLOW_TIMEOUT = Duration.ofSeconds(60);
  static final int OPTIMISTIC_TRANSACTION_ATTEMPTS = 15;

  private static final String ALLOCATION_EVENTS = PromisingEventTopics.ALLOCATION_EVENTS;
  private static final String CONNECTOR_NAME = "order-promising-correctness-outbox";
  private static final String DEBEZIUM_IMAGE = "quay.io/debezium/connect:3.5.2.Final";
  private static final Network NETWORK = Network.newNetwork();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("order_promising_correctness")
          .withUsername("order_promising")
          .withPassword("order_promising")
          .withCommand("postgres", "-c", "wal_level=logical")
          .withNetwork(NETWORK)
          .withNetworkAliases("postgres");
  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2"))
          .withListener("kafka:19092")
          .withNetwork(NETWORK)
          .withNetworkAliases("kafka");
  private static final GenericContainer<?> DEBEZIUM = debeziumConnectContainer();

  private static ConfigurableApplicationContext application;
  private static int applicationPort;
  private static boolean kafkaPaused;

  private FullPathMessagingEnvironment() {
  }

  static void startFullPath() {
    POSTGRES.start();
    KAFKA.start();
    createTopics();
    startApplication();
    DEBEZIUM.start();
    registerConnector();
    awaitConnectorRunning();
  }

  static void stopFullPath() {
    closeApplication();
    unpauseKafkaIfNecessary();
    if (DEBEZIUM.isRunning()) {
      DEBEZIUM.stop();
    }
    if (KAFKA.isRunning()) {
      KAFKA.stop();
    }
    if (POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
    NETWORK.close();
  }

  static void stopConnector() {
    DEBEZIUM.stop();
  }

  static void startConnector() {
    if (!DEBEZIUM.isRunning()) {
      DEBEZIUM.start();
    }
    awaitConnectorRunning();
  }

  static void startApplication() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.port", "0");
    properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
    properties.put("spring.datasource.username", POSTGRES.getUsername());
    properties.put("spring.datasource.password", POSTGRES.getPassword());
    properties.put("spring.flyway.enabled", "true");
    properties.put("spring.jpa.hibernate.ddl-auto", "none");
    properties.put("spring.jpa.open-in-view", "false");
    properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    properties.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    properties.put("spring.kafka.listener.concurrency", "1");
    properties.put("spring.kafka.listener.missing-topics-fatal", "false");
    properties.put("spring.kafka.listener.observation-enabled", "true");
    properties.put("archone.allocation.reconciliation-scheduler-enabled", "false");
    properties.put("management.otlp.metrics.export.enabled", "false");
    properties.put("spring.main.banner-mode", "off");

    // Builder properties are only defaults and would lose to application.properties. Command-line
    // arguments guarantee that the isolated Kafka endpoint and random HTTP port actually win.
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    application = new SpringApplicationBuilder(
        ArchoneApplication.class, FailureInjectionConfiguration.class)
        .web(WebApplicationType.SERVLET)
        .run(arguments);
    applicationPort = ((WebServerApplicationContext) application).getWebServer().getPort();
    awaitConsumersReady();
  }

  static void restartApplication() {
    closeApplication();
    startApplication();
  }

  private static void closeApplication() {
    if (application != null) {
      application.close();
      application = null;
    }
  }

  private static void awaitConsumersReady() {
    awaitCondition("application Kafka consumers to join their groups", NORMAL_FLOW_TIMEOUT, () -> {
      try (Admin admin = admin()) {
        Map<String, org.apache.kafka.clients.admin.ConsumerGroupDescription> descriptions =
            admin.describeConsumerGroups(List.of(
                    AllocationEventSubscriptions.ORDER_LIFECYCLE,
                    OrderingEventSubscriptions.ALLOCATION_RESULTS))
                .all()
                .get(5, TimeUnit.SECONDS);
        return descriptions.values().stream().allMatch(description ->
            !description.members().isEmpty());
      }
    });
  }

  private static void createTopics() {
    List<String> baseTopics = List.of(
        ORDER_EVENTS,
        ALLOCATION_EVENTS,
        InventoryEventTopics.STOCK_EVENTS);
    List<NewTopic> topics = new ArrayList<>();
    for (String topic : baseTopics) {
      topics.add(new NewTopic(topic, 3, (short) 1));
      topics.add(new NewTopic(topic + "-dlt", 3, (short) 1));
    }
    try (Admin admin = admin()) {
      admin.createTopics(topics).all().get(20, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot create correctness E2E Kafka topics", exception);
    }
  }

  private static Admin admin() {
    return Admin.create(Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000,
        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000));
  }

  private static GenericContainer<?> debeziumConnectContainer() {
    return new GenericContainer<>(DockerImageName.parse(DEBEZIUM_IMAGE))
        .withNetwork(NETWORK)
        .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
        .withEnv("GROUP_ID", "order-promising-correctness-connect")
        .withEnv("CONFIG_STORAGE_TOPIC", "correctness_connect_config")
        .withEnv("OFFSET_STORAGE_TOPIC", "correctness_connect_offsets")
        .withEnv("STATUS_STORAGE_TOPIC", "correctness_connect_status")
        .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
        .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
        .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
        .withExposedPorts(8083)
        .waitingFor(Wait.forHttp("/connectors").forPort(8083));
  }

  private static void registerConnector() {
    Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
    configuration.put("database.hostname", "postgres");
    configuration.put("database.port", 5432);
    configuration.put("database.user", POSTGRES.getUsername());
    configuration.put("database.password", POSTGRES.getPassword());
    configuration.put("database.dbname", POSTGRES.getDatabaseName());
    configuration.put("plugin.name", "pgoutput");
    configuration.put("topic.prefix", "order-promising-correctness");
    configuration.put("table.include.list", "public.event_outbox");
    configuration.put("slot.name", "order_promising_correctness_outbox_slot");
    configuration.put("publication.autocreate.mode", "filtered");
    configuration.put("transforms", "outbox");
    configuration.put("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter");
    configuration.put("transforms.outbox.route.by.field", "route");
    configuration.put("transforms.outbox.route.topic.replacement", "${routedByValue}");
    configuration.put("transforms.outbox.table.field.event.key", "partition_key");
    configuration.put("transforms.outbox.table.expand.json.payload", true);
    configuration.put("transforms.outbox.table.fields.additional.placement",
        "type:header:eventType,headers:header:messageHeaders");
    configuration.put("key.converter", "org.apache.kafka.connect.storage.StringConverter");
    configuration.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
    configuration.put("value.converter.schemas.enable", false);
    postConnect("/connectors", Map.of("name", CONNECTOR_NAME, "config", configuration));
  }

  static void awaitConnectorRunning() {
    awaitCondition("Debezium connector to be RUNNING", NORMAL_FLOW_TIMEOUT, () -> {
      JsonNode status = getConnect("/connectors/" + CONNECTOR_NAME + "/status");
      return "RUNNING".equals(status.path("connector").path("state").asText())
          && status.path("tasks").isArray()
          && status.path("tasks").size() == 1
          && "RUNNING".equals(status.path("tasks").path(0).path("state").asText());
    });
  }

  private static void postConnect(String path, Object body) {
    try {
      HttpResponse<String> response = HTTP.send(
          HttpRequest.newBuilder(connectUri(path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Kafka Connect returned " + response.statusCode() + ": " + response.body());
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot register Debezium connector", exception);
    }
  }

  private static JsonNode getConnect(String path) {
    try {
      HttpResponse<String> response = HTTP.send(
          HttpRequest.newBuilder(connectUri(path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 ? JSON.readTree(response.body()) : JSON.nullNode();
    } catch (Exception exception) {
      return JSON.nullNode();
    }
  }

  private static URI connectUri(String path) {
    return URI.create("http://" + DEBEZIUM.getHost() + ":"
        + DEBEZIUM.getMappedPort(8083) + path);
  }

  static UUID placeOrder(String sku, int quantity, String externalOrderNo) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("ownerId", OrderFixtures.OWNER_ID);
    requestBody.put("externalOrderNo", externalOrderNo);
    requestBody.put("shipToZone", "100");
    requestBody.put("shipToAddress", "台北市中正區測試路 1 號");
    requestBody.put("promisedDeliveryDate", LocalDate.now().plusDays(3).toString());
    requestBody.put("facilityId", OrderFixtures.FACILITY_ID);
    requestBody.put("lines", List.of(Map.of("skuCode", sku, "quantity", quantity)));
    try {
      HttpResponse<String> response = HTTP.send(
          HttpRequest.newBuilder(applicationUri("/orders"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isBetween(200, 299);
      return UUID.fromString(JSON.readTree(response.body()).path("orderId").asText());
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot place correctness E2E order", exception);
    }
  }

  static String orderStatus(UUID orderId) {
    try {
      HttpResponse<String> response = HTTP.send(
          HttpRequest.newBuilder(applicationUri("/orders/" + orderId))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return "HTTP-" + response.statusCode();
      }
      return JSON.readTree(response.body()).path("status").asText();
    } catch (Exception exception) {
      return "UNAVAILABLE";
    }
  }

  static void awaitOrderStatus(UUID orderId, String status, Duration timeout) {
    awaitCondition("order " + orderId + " to reach " + status, timeout,
        () -> status.equals(orderStatus(orderId)));
  }

  private static URI applicationUri(String path) {
    return URI.create("http://127.0.0.1:" + applicationPort + path);
  }

  static void seedAvailableStock(String sku, int quantity) {
    OrderFixtures.seedCatalog(jdbc(), OrderFixtures.OWNER_ID, sku);
    jdbc().update("""
        INSERT INTO stock_pools (
            id, owner_id, location_id, sku_code, in_date, expiry_date,
            on_hand_quantity, reserved_quantity, version, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, CURRENT_TIMESTAMP)
        """,
        UUID.randomUUID(),
        OrderFixtures.OWNER_ID,
        OrderFixtures.LOCATION_ID,
        sku,
        LocalDate.now().minusDays(10),
        LocalDate.now().plusYears(1),
        quantity);
  }

  static UUID awaitOutboxEvent(UUID orderId, String eventType) {
    AtomicReference<UUID> found = new AtomicReference<>();
    awaitCondition("Outbox " + eventType + " for order " + orderId,
        NORMAL_FLOW_TIMEOUT, () -> {
          List<UUID> ids = jdbc().queryForList("""
              SELECT id FROM event_outbox
               WHERE aggregateid = ? AND type = ?
               ORDER BY timestamp, id
              """, UUID.class, orderId.toString(), eventType);
          if (ids.size() == 1) {
            found.set(ids.getFirst());
            return true;
          }
          return false;
        });
    return found.get();
  }

  static int outboxCount(UUID orderId) {
    return jdbc().queryForObject(
        "SELECT COUNT(*) FROM event_outbox WHERE aggregateid = ?",
        Integer.class,
        orderId.toString());
  }

  static int inboxClaimCount(String subscriberId, UUID eventId) {
    return jdbc().queryForObject("""
        SELECT COUNT(*) FROM event_inbox
         WHERE subscriber_id = ? AND event_id = ?
        """, Integer.class, subscriberId, eventId);
  }

  static int reservedQuantity(String sku) {
    return jdbc().queryForObject("""
        SELECT COALESCE(SUM(reserved_quantity), 0) FROM stock_pools
         WHERE owner_id = ? AND location_id = ? AND sku_code = ?
        """, Integer.class, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID, sku);
  }

  static int pickingCount(UUID orderId) {
    return jdbc().queryForObject(
        "SELECT COUNT(*) FROM stock_pickings WHERE order_id = ?",
        Integer.class,
        orderId);
  }

  static int moveCount(UUID orderId) {
    return jdbc().queryForObject("""
        SELECT COUNT(*)
          FROM stock_moves movement
          JOIN order_lines line ON line.id = movement.order_line_id
         WHERE line.order_id = ?
        """, Integer.class, orderId);
  }

  static int moveLineQuantity(UUID orderId) {
    return jdbc().queryForObject("""
        SELECT COALESCE(SUM(detail.quantity), 0)
          FROM stock_move_lines detail
          JOIN stock_moves movement ON movement.id = detail.move_id
          JOIN order_lines line ON line.id = movement.order_line_id
         WHERE line.order_id = ?
        """, Integer.class, orderId);
  }

  private static JdbcTemplate jdbc() {
    return application.getBean(JdbcTemplate.class);
  }

  static AllocationFailureInjector failureInjector() {
    return application.getBean(AllocationFailureInjector.class);
  }

  static double duplicateOutcomeCount() {
    MeterRegistry registry = application.getBean(MeterRegistry.class);
    return registry.find(MessagingObservationNames.CONSUMER)
        .tag(MessagingObservationTags.SUBSCRIBER_ID,
            AllocationEventSubscriptions.ORDER_LIFECYCLE)
        .tag(MessagingObservationTags.OUTCOME, "duplicate")
        .timers()
        .stream()
        .mapToDouble(timer -> timer.count())
        .sum();
  }

  static KafkaConsumer<String, String> probeAtEnd(String topic) {
    Properties properties = new Properties();
    properties.putAll(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "correctness-probe-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
    consumer.subscribe(List.of(topic));
    awaitCondition("probe assignment for " + topic, Duration.ofSeconds(20), () -> {
      consumer.poll(Duration.ofMillis(100));
      return !consumer.assignment().isEmpty();
    });
    consumer.seekToEnd(consumer.assignment());
    return consumer;
  }

  static ConsumerRecord<String, String> awaitEvent(
      KafkaConsumer<String, String> consumer,
      UUID eventId,
      Duration timeout
  ) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> record : records) {
        if (eventId.toString().equals(textHeader(record, "id"))) {
          return record;
        }
      }
    }
    throw new AssertionError("Timed out waiting for Kafka event " + eventId);
  }

  static ProducerRecord<String, String> copyOf(ConsumerRecord<String, String> source) {
    return new ProducerRecord<>(
        source.topic(),
        source.partition(),
        source.timestamp(),
        source.key(),
        source.value(),
        new RecordHeaders(source.headers()));
  }

  static void publish(ProducerRecord<String, String> record) {
    Properties properties = new Properties();
    properties.putAll(Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ProducerConfig.ACKS_CONFIG, "all"));
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer())) {
      producer.send(record).get(10, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot publish correctness E2E Kafka record", exception);
    }
  }

  static String textHeader(ConsumerRecord<?, ?> record, String name) {
    return new String(requiredHeader(record.headers(), name), StandardCharsets.UTF_8);
  }

  static int intHeader(ConsumerRecord<?, ?> record, String name) {
    return ByteBuffer.wrap(requiredHeader(record.headers(), name)).getInt();
  }

  static long longHeader(ConsumerRecord<?, ?> record, String name) {
    return ByteBuffer.wrap(requiredHeader(record.headers(), name)).getLong();
  }

  private static byte[] requiredHeader(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    assertThat(header).as("Kafka header %s", name).isNotNull();
    assertThat(header.value()).as("Kafka header value %s", name).isNotNull();
    return header.value();
  }

  static void pauseKafka() {
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    kafkaPaused = true;
  }

  static void unpauseKafkaIfNecessary() {
    if (kafkaPaused) {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
      kafkaPaused = false;
    }
  }

  static void awaitCondition(
      String description,
      Duration timeout,
      CheckedCondition condition
  ) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Throwable lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        if (condition.test()) {
          return;
        }
      } catch (Throwable failure) {
        lastFailure = failure;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, exception);
      }
    }
    AssertionError timeoutFailure = new AssertionError("Timed out waiting for " + description);
    if (lastFailure != null) {
      timeoutFailure.initCause(lastFailure);
    }
    throw timeoutFailure;
  }

  @FunctionalInterface
  interface CheckedCondition {
    boolean test() throws Exception;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FailureInjectionConfiguration {

    @Bean
    AllocationFailureInjector allocationFailureInjector() {
      return new AllocationFailureInjector();
    }
  }

  /** Injects a failure after business writes, while the consumer transaction is still open. */
  @Aspect
  static class AllocationFailureInjector {

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger failuresRemaining = new AtomicInteger();

    void failNext(int attempts) {
      reset();
      failuresRemaining.set(attempts);
    }

    void allowSuccess() {
      failuresRemaining.set(0);
    }

    int invocations() {
      return invocations.get();
    }

    void reset() {
      invocations.set(0);
      failuresRemaining.set(0);
    }

    @Around("execution(* com.flowzati.archone.stock.application.movement."
        + "MovementAssigner.assign(..))")
    Object failAfterBusinessWrites(ProceedingJoinPoint joinPoint) throws Throwable {
      Object result = joinPoint.proceed();
      invocations.incrementAndGet();
      if (failuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
        throw new OptimisticLockingFailureException("forced correctness E2E contention");
      }
      return result;
    }
  }
}
