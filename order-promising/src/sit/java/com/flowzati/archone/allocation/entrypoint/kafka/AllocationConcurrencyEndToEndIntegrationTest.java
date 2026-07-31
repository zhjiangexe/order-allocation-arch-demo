package com.flowzati.archone.allocation.entrypoint.kafka;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, AllocationConcurrencyEndToEndIntegrationTest.ConflictConfiguration.class})
class AllocationConcurrencyEndToEndIntegrationTest {

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventDispatcher dispatcher;

  @Autowired
  private AllocationKafkaIntegrationEventConsumer consumer;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockPoolRepository stockPoolRepository;

  @Autowired
  private StockReservationRepository stockReservationRepository;

  @Autowired
  private JpaEventInboxRepository inboxRepository;

  @Autowired
  private JpaOutboxRepository outboxRepository;

  @Autowired
  private MeterRegistry meterRegistry;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private AllocationConflictInjector conflictInjector;

  @AfterEach
  void clearDatabase() {
    conflictInjector.reset();
    jdbcTemplate.execute("DELETE FROM event_outbox");
    jdbcTemplate.execute("DELETE FROM event_inbox");
    jdbcTemplate.execute("DELETE FROM stock_reservations");
    jdbcTemplate.execute("DELETE FROM order_lines");
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
    jdbcTemplate.execute("DELETE FROM skus");
    jdbcTemplate.execute("DELETE FROM products");
    jdbcTemplate.execute("DELETE FROM owner_nodes");
    jdbcTemplate.execute("DELETE FROM owners");
    jdbcTemplate.execute("DELETE FROM fulfillment_nodes");
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "SKU-CONCURRENT", "SKU-EXHAUSTED");
  }

  @Test
  @DisplayName("兩張訂單競爭同一 ATP 時應重試並收斂為一張配置、一張欠單")
  void shouldRetryConcurrentAllocationWithoutOverselling() throws Exception {
    UUID stockPoolId = UUID.randomUUID();
    UUID firstOrderId = IdGenerator.nextId();
    UUID secondOrderId = IdGenerator.nextId();
    Instant receivedAt = Instant.now().minusSeconds(1);
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-CONCURRENT", 3, 0));
    orderRepository.save(OrderFixtures.pendingOrder(firstOrderId, "SKU-CONCURRENT", 3, receivedAt));
    orderRepository.save(OrderFixtures.pendingOrder(secondOrderId, "SKU-CONCURRENT", 3, receivedAt));

    OrderPlacedIntegrationEvent firstEvent = new OrderPlacedIntegrationEvent(UUID.randomUUID(), firstOrderId, receivedAt);
    OrderPlacedIntegrationEvent secondEvent = new OrderPlacedIntegrationEvent(UUID.randomUUID(), secondOrderId, receivedAt);
    conflictInjector.blockFirstTwoAllocationAttempts();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> consume(firstEvent));
      Future<?> second = executor.submit(() -> consume(secondEvent));
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    // 先把配貨結果餵回 ordering，訂單狀態才會推進——讀狀態一定要在這之後。
    outcomeDrain().drain();

    List<OrderStatus> statuses = List.of(
        orderRepository.findById(firstOrderId).orElseThrow().getStatus(),
        orderRepository.findById(secondOrderId).orElseThrow().getStatus());
    assertThat(statuses).containsExactlyInAnyOrder(OrderStatus.ALLOCATED, OrderStatus.BACKORDERED);
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getReservedQuantity()).isEqualTo(3);
      assertThat(pool.getReservedQuantity()).isLessThanOrEqualTo(pool.getOnHandQuantity());
    });
    // 恰好一張拿到預留：兩張都拿到代表超賣，都沒拿到代表兩張都白白重試到耗盡。
    assertThat(!activeReservationsOf(firstOrderId).isEmpty()
        ^ !activeReservationsOf(secondOrderId).isEmpty()).isTrue();
    assertThat(inboxRepository.findById(firstEvent.getEventId())).isPresent();
    assertThat(inboxRepository.findById(secondEvent.getEventId())).isPresent();
    assertThat(outboxRepository.findAll().stream().map(outbox -> outbox.getEventType()))
        .contains(OrderAllocatedIntegrationEvent.class.getSimpleName(),
            BackorderCreatedIntegrationEvent.class.getSimpleName());
    assertThat(conflictInjector.invocations()).isGreaterThanOrEqualTo(3);
  }

  @Test
  @DisplayName("三次技術衝突耗盡時應回滾業務資料與 Inbox Outbox 並記錄 metric")
  void shouldRollbackAllocationWhenConcurrencyRetryIsExhausted() {
    UUID stockPoolId = UUID.randomUUID();
    UUID orderId = IdGenerator.nextId();
    Instant receivedAt = Instant.now().minusSeconds(1);
    stockPoolRepository.save(StockFixtures.unexpiredBatch(stockPoolId, "SKU-EXHAUSTED", 3, 0));
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-EXHAUSTED", 3, receivedAt));
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt);
    double metricBefore = exhaustedMetricCount();
    conflictInjector.failFirstAllocationAttempts(3);

    assertThatThrownBy(() -> consume(event))
        .isInstanceOf(AllocationConcurrencyExhaustedException.class);

    assertThat(conflictInjector.invocations()).isEqualTo(3);
    outcomeDrain().drain();
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isZero());
    assertThat(activeReservationsOf(orderId)).isEmpty();
    assertThat(inboxRepository.findById(event.getEventId())).isEmpty();
    assertThat(outboxRepository.count()).isZero();
    assertThat(exhaustedMetricCount()).isEqualTo(metricBefore + 1.0);
  }

  private void consume(OrderPlacedIntegrationEvent event) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        OrderingEventTopics.ORDER_EVENTS,
        0,
        0,
        event.getOrderId().toString(),
        serialize(event));
    record.headers().add("id", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", OrderPlacedIntegrationEvent.class.getSimpleName()
        .getBytes(StandardCharsets.UTF_8));
    consumer.consumeOrderingEvent(record);
  }

  private String serialize(OrderPlacedIntegrationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize test integration event", exception);
    }
  }

  private double exhaustedMetricCount() {
    var counter = meterRegistry.find("order_allocation_retry_exhausted_total")
        .tag("operation", "allocate-order")
        .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ConflictConfiguration {

    @Bean
    AllocationConflictInjector allocationConflictInjector() {
      return new AllocationConflictInjector();
    }
  }

  @Aspect
  static class AllocationConflictInjector {

    private final AtomicInteger invocations = new AtomicInteger();
    private volatile int forcedFailures;
    private volatile CountDownLatch concurrentAttempts;

    void blockFirstTwoAllocationAttempts() {
      concurrentAttempts = new CountDownLatch(2);
    }

    void failFirstAllocationAttempts(int attempts) {
      forcedFailures = attempts;
    }

    @Around("execution(* com.flowzati.archone.allocation.application.coordinator."
        + "OrderAllocationCoordinator.allocateOrder(..))")
    public Object injectConflict(ProceedingJoinPoint joinPoint) throws Throwable {
      int invocation = invocations.incrementAndGet();
      CountDownLatch latch = concurrentAttempts;
      if (latch != null && invocation <= 2) {
        latch.countDown();
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Concurrent allocation attempts did not reach the barrier");
        }
      }

      Object result = joinPoint.proceed();
      if (invocation <= forcedFailures) {
        throw new OptimisticLockingFailureException("forced allocation conflict");
      }
      return result;
    }

    int invocations() {
      return invocations.get();
    }

    void reset() {
      invocations.set(0);
      forcedFailures = 0;
      concurrentAttempts = null;
    }
  }

  /**
   * 這張單目前還有效的預留。
   *
   * <p>{@code stock_reservations} 指向 {@code order_lines}，所以要先從訂單取行的 id——與
   * {@code ReleaseReservationUsecase} 走同一條路。回的是清單而不是單筆：一條行跨三批就有
   * 三筆預留。
   */
  private java.util.List<com.flowzati.archone.allocation.domain.model.StockReservation>
      activeReservationsOf(java.util.UUID orderId) {
    return stockReservationRepository.findActiveByOrderId(orderId);
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
