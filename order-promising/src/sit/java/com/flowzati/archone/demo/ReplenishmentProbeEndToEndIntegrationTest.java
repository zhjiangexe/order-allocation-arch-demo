package com.flowzati.archone.demo;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.repository.StockPoolRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.KafkaTestConfiguration;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

/**
 * 補貨探針的端到端驗證：HTTP → 真實 Kafka broker → allocation consumer → 配置決策。
 *
 * <p>其他 allocation SIT 都直接呼叫 consumer、繞過 broker；這個測試刻意不繞，因為它要
 * 驗證的正是「探針送出的訊息真的能被我們自己的 consumer 接受」——訊息 header、payload
 * 與 key 的契約若有任何一項不符，dispatcher 會拒收，而那只有走真實 broker 才看得出來。
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "archone.allocation.partition-key-strategy=stock")
@ActiveProfiles({"test", "dev"})
@Import({PostgreSQLTestConfiguration.class, KafkaTestConfiguration.class})
class ReplenishmentProbeEndToEndIntegrationTest {

  private static final String SKU = "SKU-PROBE-E2E";
  /** 規格的界線：配置結果必須在觸發後 10 秒內可觀察到。 */
  private static final Duration DECISION_WINDOW = Duration.ofSeconds(10);

  @LocalServerPort
  private int port;

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher dispatcher;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private KafkaListenerEndpointRegistry listenerRegistry;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-PROBE-E2E");
  }

  @Test
  @DisplayName("觸發補貨探針後，排隊中的 backorder 應在 10 秒內依 FIFO 轉為 ALLOCATED")
  void shouldWakeQueuedBackordersWithinTheObservableWindow() {
    stockPoolRepository.save(StockFixtures.unexpiredBatch(SKU, 0, 0));
    // 必須早於現在：Order.markAllocated 會拒絕早於 backOrderedSince 的 allocatedAt，
    // 寫死的未來時間會讓配置在領域層就被擋下，而不是真的驗到訊息路徑
    Instant firstBackorderedAt = Instant.now().minusSeconds(60);
    UUID firstOrderId = backorder(firstBackorderedAt, 3);
    UUID secondOrderId = backorder(firstBackorderedAt.plusSeconds(1), 3);
    UUID blockedOrderId = backorder(firstBackorderedAt.plusSeconds(2), 999);
    awaitListenersAssigned();

    HttpResponse<String> accepted = triggerReplenishment(6);

    assertThat(accepted.statusCode()).isEqualTo(202);
    assertThat(readJson(accepted.body()).path("eventId").asText()).isNotBlank();

    awaitAllocated(List.of(firstOrderId, secondOrderId));
    // 補貨量剛好吃完前兩張；第三張要不到，證明喚醒佇列走的是嚴格 FIFO 而不是能配就配
    outcomeDrain().drain();
    assertThat(statusOf(blockedOrderId)).isEqualTo(OrderStatus.BACKORDERED);
  }

  private HttpResponse<String> triggerReplenishment(int quantity) {
    try {
      return HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/demo/replenish"))
              .header("Content-Type", "application/json")
              // 五個維度都要帶：它們合起來決定這批貨加到哪一列。缺任一個回 400，
              // 缺貨主更決定不了要喚醒誰的佇列。
              .POST(HttpRequest.BodyPublishers.ofString("""
                  {
                    "ownerId": "%s",
                    "nodeId": "%s",
                    "sku": "%s",
                    "inDate": "%s",
                    "expiryDate": "%s",
                    "quantity": %d
                  }
                  """.formatted(
                      OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, SKU,
                      StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON, quantity)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot call replenishment probe", exception);
    }
  }

  private JsonNode readJson(String body) {
    try {
      return objectMapper.readTree(body);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot parse probe response: " + body, exception);
    }
  }

  private UUID backorder(Instant backorderedAt, int quantity) {
    UUID orderId = IdGenerator.nextId();
    MovementFixtures.saveQueuedOrder(orderRepository, jdbcTemplate, OrderFixtures.backorderedOrder(
        orderId, SKU, quantity, backorderedAt.minusSeconds(1), backorderedAt));
    return orderId;
  }

  /**
   * 先等 consumer 拿到 partition 指派，再開始計時。10 秒界線衡量的是配置延遲，不是
   * consumer group 的暖機時間——把兩者混在一起會讓這個斷言變得沒有意義。
   */
  private void awaitListenersAssigned() {
    outcomeDrain().drain();
    await(Duration.ofSeconds(60), () -> listenerRegistry.getListenerContainers().stream()
        .allMatch(MessageListenerContainer::isRunning));
  }

  private void awaitAllocated(List<UUID> orderIds) {
    // 每一輪都 drain：探針是非同步的，第一次輪詢時 outbox 裡還不見得有配貨結果。
    // 同一個 drain 實例貫穿整個等待，已餵過的事件不會重送。
    var drain = outcomeDrain();
    await(DECISION_WINDOW, () -> {
      drain.drain();
      return orderIds.stream().allMatch(id -> statusOf(id) == OrderStatus.ALLOCATED);
    });
    assertThat(orderIds)
        .withFailMessage(() -> "訂單未在 " + DECISION_WINDOW.toSeconds() + " 秒內完成配置。"
            // 這兩個數字能分辨「訊息沒送到 consumer」與「送到了但處理失敗回滾」
            + "狀態=" + orderIds.stream().map(this::statusOf).toList()
            + "；inbox claims=" + jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_inbox", Integer.class)
            + "；onHand=" + StockFixtures.reloadUnexpiredBatch(stockPoolRepository, SKU).orElseThrow().getOnHandQuantity())
        .allSatisfy(id -> assertThat(statusOf(id)).isEqualTo(OrderStatus.ALLOCATED));
  }

  private OrderStatus statusOf(UUID orderId) {
    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private static void await(Duration timeout, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting condition", exception);
      }
    }
  }

  /**
   * 把 outbox 的配貨結果餵回 ordering。
   *
   * <p>配貨只寫自己的表並發事件，訂單狀態由 ordering 收到後推進；SIT 沒有 Debezium，那一段
   * 得自己走完——production 裡是 Kafka 做這件事。
   */
  private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
    return new com.flowzati.archone.testsupport.AllocationOutcomeDrain(jdbcTemplate, dispatcher);
  }
}
