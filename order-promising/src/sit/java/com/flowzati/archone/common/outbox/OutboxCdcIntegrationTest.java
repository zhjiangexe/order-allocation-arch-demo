package com.flowzati.archone.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCdcIntegrationTest {

  private static final String ORDER_EVENTS_TOPIC = OutboxRoutes.ORDERING_ORDER_EVENTS;
  private static final String PRIMARY_CONNECTOR = "order-promising-outbox";
  private static final String DEBEZIUM_IMAGE = "quay.io/debezium/connect:3.5.2.Final";
  private static final Network NETWORK = Network.newNetwork();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("order_promising_cdc")
      .withUsername("order_promising")
      .withPassword("order_promising")
      .withCommand("postgres", "-c", "wal_level=logical")
      .withNetwork(NETWORK)
      .withNetworkAliases("postgres");
  private static final KafkaContainer KAFKA = new KafkaContainer(
      DockerImageName.parse("apache/kafka-native:4.1.2"))
      .withListener("kafka:19092")
      .withNetwork(NETWORK)
      .withNetworkAliases("kafka");
  private static final GenericContainer<?> DEBEZIUM = debeziumConnectContainer();

  @BeforeAll
  static void startInfrastructure() {
    POSTGRES.start();
    KAFKA.start();
    migrateSchema();
    DEBEZIUM.start();
    registerConnector(DEBEZIUM, PRIMARY_CONNECTOR, "order_promising_outbox_slot");
    awaitConnectorRunning(DEBEZIUM, PRIMARY_CONNECTOR);
  }

  @AfterAll
  static void stopInfrastructure() {
    DEBEZIUM.stop();
    KAFKA.stop();
    POSTGRES.stop();
    NETWORK.close();
  }

  @Test
  void shouldRouteCommittedOutboxRowsResumeAfterRestartAndReplaySnapshot() {
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(ORDER_EVENTS_TOPIC));

      UUID firstEventId = appendOutboxEvent("OrderPlacedIntegrationEvent", ORDER_EVENTS_TOPIC,
          "{\"eventId\":\"first\",\"orderId\":\"order-1\"}");
      ConsumerRecord<String, String> firstRecord = awaitEvent(consumer, firstEventId);
      assertThat(firstRecord.key()).isEqualTo("order-1");
      assertThat(firstRecord.value()).contains("\"eventId\":\"first\"");
      assertThat(header(firstRecord, "id")).isEqualTo(firstEventId.toString());
      assertThat(header(firstRecord, "eventType")).isEqualTo("OrderPlacedIntegrationEvent");

      DEBEZIUM.stop();
      DEBEZIUM.start();
      awaitConnectorRunning(DEBEZIUM, PRIMARY_CONNECTOR);

      UUID secondEventId = appendOutboxEvent("OrderCancelledIntegrationEvent", ORDER_EVENTS_TOPIC,
          "{\"eventId\":\"second\",\"orderId\":\"order-2\"}");
      ConsumerRecord<String, String> secondRecord = awaitEvent(consumer, secondEventId);
      assertThat(secondRecord.key()).isEqualTo("order-2");
      assertThat(header(secondRecord, "id")).isEqualTo(secondEventId.toString());

      GenericContainer<?> replayConnector = debeziumConnectContainer();
      try {
        replayConnector.start();
        String replayConnectorName = "order-promising-outbox-replay";
        registerConnector(replayConnector, replayConnectorName, "order_promising_outbox_replay_slot");
        awaitConnectorRunning(replayConnector, replayConnectorName);

        ConsumerRecord<String, String> replayedFirstRecord = awaitEvent(consumer, firstEventId);
        assertThat(header(replayedFirstRecord, "id")).isEqualTo(firstEventId.toString());
        assertThat(replayedFirstRecord.value()).contains("\"eventId\":\"first\"");
      } finally {
        replayConnector.stop();
      }
    }
  }

  private static GenericContainer<?> debeziumConnectContainer() {
    return new GenericContainer<>(DockerImageName.parse(DEBEZIUM_IMAGE))
        .withNetwork(NETWORK)
        .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
        .withEnv("GROUP_ID", "order-promising-connect")
        .withEnv("CONFIG_STORAGE_TOPIC", "order_promising_connect_config")
        .withEnv("OFFSET_STORAGE_TOPIC", "order_promising_connect_offsets")
        .withEnv("STATUS_STORAGE_TOPIC", "order_promising_connect_status")
        .withExposedPorts(8083)
        .waitingFor(Wait.forHttp("/connectors").forPort(8083));
  }

  private static void registerConnector(GenericContainer<?> connect, String name, String slotName) {
    Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
    configuration.put("database.hostname", "postgres");
    configuration.put("database.port", 5432);
    configuration.put("database.user", POSTGRES.getUsername());
    configuration.put("database.password", POSTGRES.getPassword());
    configuration.put("database.dbname", POSTGRES.getDatabaseName());
    configuration.put("plugin.name", "pgoutput");
    configuration.put("topic.prefix", "order-promising");
    configuration.put("table.include.list", "public.event_outbox");
    configuration.put("slot.name", slotName);
    configuration.put("publication.autocreate.mode", "filtered");
    configuration.put("transforms", "outbox");
    configuration.put("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter");
    configuration.put("transforms.outbox.route.by.field", "route");
    configuration.put("transforms.outbox.route.topic.replacement", "${routedByValue}");
    configuration.put("transforms.outbox.table.expand.json.payload", true);
    configuration.put("transforms.outbox.table.fields.additional.placement", "type:header:eventType");
    configuration.put("key.converter", "org.apache.kafka.connect.storage.StringConverter");
    configuration.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
    configuration.put("value.converter.schemas.enable", false);
    post(connect, "/connectors", Map.of("name", name, "config", configuration));
  }

  private static void awaitConnectorRunning(GenericContainer<?> connect, String name) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      JsonNode status = get(connect, "/connectors/" + name + "/status");
      if ("RUNNING".equals(status.path("connector").path("state").asText())
          && "RUNNING".equals(status.path("tasks").path(0).path("state").asText())) {
        return;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for connector", exception);
      }
    }
    throw new AssertionError("Timed out waiting for connector " + name + " to run");
  }

  private static void post(GenericContainer<?> connect, String path, Object body) {
    try {
      HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(connectUri(connect, path))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
          .build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Kafka Connect request failed: " + response.body());
      }
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot register Debezium connector", exception);
    }
  }

  private static JsonNode get(GenericContainer<?> connect, String path) {
    try {
      HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(connectUri(connect, path))
          .GET()
          .build(), HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200 ? JSON.readTree(response.body()) : JSON.nullNode();
    } catch (Exception exception) {
      return JSON.nullNode();
    }
  }

  private static URI connectUri(GenericContainer<?> connect, String path) {
    return URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + path);
  }

  private static void migrateSchema() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static UUID appendOutboxEvent(String eventType, String route, String payload) {
    UUID eventId = UUID.randomUUID();
    try (Connection connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO event_outbox (id, aggregatetype, aggregateid, type, route, payload, timestamp)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            """)) {
      statement.setObject(1, eventId);
      statement.setString(2, OutboxAggregateTypes.ORDER);
      statement.setString(3, eventType.equals("OrderPlacedIntegrationEvent") ? "order-1" : "order-2");
      statement.setString(4, eventType);
      statement.setString(5, route);
      statement.setString(6, payload);
      statement.setTimestamp(7, Timestamp.from(Instant.now()));
      statement.executeUpdate();
      return eventId;
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot append outbox event", exception);
    }
  }

  private static KafkaConsumer<String, String> consumer() {
    Properties properties = new Properties();
    properties.putAll(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "outbox-cdc-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
    return new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
  }

  private static ConsumerRecord<String, String> awaitEvent(
      KafkaConsumer<String, String> consumer,
      UUID eventId
  ) {
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
      polled.forEach(records::add);
      for (ConsumerRecord<String, String> record : records) {
        if (eventId.toString().equals(header(record, "id"))) {
          return record;
        }
      }
    }
    throw new AssertionError("Timed out waiting for Outbox event " + eventId
        + "; connector status=" + get(DEBEZIUM, "/connectors/" + PRIMARY_CONNECTOR + "/status")
        + "\n" + DEBEZIUM.getLogs());
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
