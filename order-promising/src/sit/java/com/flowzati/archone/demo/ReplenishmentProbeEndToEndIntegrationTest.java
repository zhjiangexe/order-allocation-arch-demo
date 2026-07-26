package com.flowzati.archone.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.KafkaTestConfiguration;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
    properties = "archone.allocation.partition-key-strategy=sku")
@ActiveProfiles({"test", "dev"})
@Import({PostgreSQLTestConfiguration.class, KafkaTestConfiguration.class})
class ReplenishmentProbeEndToEndIntegrationTest {

  private static final String SKU = "SKU-PROBE-E2E";
  /** 規格的界線：配置結果必須在觸發後 10 秒內可觀察到。 */
  private static final Duration DECISION_WINDOW = Duration.ofSeconds(10);

  @LocalServerPort
  private int port;

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

  @Test
  @DisplayName("觸發補貨探針後，排隊中的 backorder 應在 10 秒內依 FIFO 轉為 ALLOCATED")
  void shouldWakeQueuedBackordersWithinTheObservableWindow() {
    stockPoolRepository.save(new StockPool(UUID.randomUUID(), SKU, 0, 0, null));
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
    assertThat(statusOf(blockedOrderId)).isEqualTo(OrderStatus.BACKORDERED);
  }

  private HttpResponse<String> triggerReplenishment(int quantity) {
    try {
      return HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/demo/replenish"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(
                  "{\"sku\":\"" + SKU + "\",\"quantity\":" + quantity + "}"))
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
    UUID orderId = UUID.randomUUID();
    orderRepository.save(Order.rehydrate(
        orderId, SKU, quantity, OrderStatus.BACKORDERED,
        backorderedAt.minusSeconds(1), null, backorderedAt, null, null));
    return orderId;
  }

  /**
   * 先等 consumer 拿到 partition 指派，再開始計時。10 秒界線衡量的是配置延遲，不是
   * consumer group 的暖機時間——把兩者混在一起會讓這個斷言變得沒有意義。
   */
  private void awaitListenersAssigned() {
    await(Duration.ofSeconds(60), () -> listenerRegistry.getListenerContainers().stream()
        .allMatch(MessageListenerContainer::isRunning));
  }

  private void awaitAllocated(List<UUID> orderIds) {
    await(DECISION_WINDOW,
        () -> orderIds.stream().allMatch(id -> statusOf(id) == OrderStatus.ALLOCATED));
    assertThat(orderIds)
        .withFailMessage(() -> "訂單未在 " + DECISION_WINDOW.toSeconds() + " 秒內完成配置。"
            // 這兩個數字能分辨「訊息沒送到 consumer」與「送到了但處理失敗回滾」
            + "狀態=" + orderIds.stream().map(this::statusOf).toList()
            + "；inbox claims=" + jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_inbox", Integer.class)
            + "；onHand=" + stockPoolRepository.findBySku(SKU).orElseThrow().getOnHandQuantity())
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
}
