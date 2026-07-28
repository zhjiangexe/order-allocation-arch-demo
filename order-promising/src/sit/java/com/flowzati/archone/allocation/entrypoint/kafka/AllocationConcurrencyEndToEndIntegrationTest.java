package com.flowzati.archone.allocation.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.allocation.application.event.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.allocation.application.event.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.allocation.application.retry.AllocationConcurrencyExhaustedException;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.JpaEventInboxRepository;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.common.outbox.infrastructure.repository.JpaOutboxRepository;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.testsupport.OrderFixtures;
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
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;

@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({PostgreSQLTestConfiguration.class, AllocationConcurrencyEndToEndIntegrationTest.ConflictConfiguration.class})
class AllocationConcurrencyEndToEndIntegrationTest {

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
    jdbcTemplate.execute("DELETE FROM orders");
    jdbcTemplate.execute("DELETE FROM stock_pools");
  }

  @Test
  @DisplayName("兩張訂單競爭同一 ATP 時應重試並收斂為一張配置、一張欠單")
  void shouldRetryConcurrentAllocationWithoutOverselling() throws Exception {
    UUID stockPoolId = UUID.randomUUID();
    UUID firstOrderId = UUID.randomUUID();
    UUID secondOrderId = UUID.randomUUID();
    Instant placedAt = Instant.now().minusSeconds(1);
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-CONCURRENT", 3, 0, null));
    orderRepository.save(OrderFixtures.pendingOrder(firstOrderId, "SKU-CONCURRENT", 3, placedAt));
    orderRepository.save(OrderFixtures.pendingOrder(secondOrderId, "SKU-CONCURRENT", 3, placedAt));

    OrderPlacedIntegrationEvent firstEvent = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), firstOrderId, "SKU-CONCURRENT", 3, placedAt);
    OrderPlacedIntegrationEvent secondEvent = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), secondOrderId, "SKU-CONCURRENT", 3, placedAt);
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

    List<OrderStatus> statuses = List.of(
        orderRepository.findById(firstOrderId).orElseThrow().getStatus(),
        orderRepository.findById(secondOrderId).orElseThrow().getStatus());
    assertThat(statuses).containsExactlyInAnyOrder(OrderStatus.ALLOCATED, OrderStatus.BACKORDERED);
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool -> {
      assertThat(pool.getReservedQuantity()).isEqualTo(3);
      assertThat(pool.getReservedQuantity()).isLessThanOrEqualTo(pool.getOnHandQuantity());
    });
    assertThat(stockReservationRepository.findActiveByOrderId(firstOrderId).isPresent()
        ^ stockReservationRepository.findActiveByOrderId(secondOrderId).isPresent()).isTrue();
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
    UUID orderId = UUID.randomUUID();
    Instant placedAt = Instant.now().minusSeconds(1);
    stockPoolRepository.save(new StockPool(stockPoolId, "SKU-EXHAUSTED", 3, 0, null));
    orderRepository.save(OrderFixtures.pendingOrder(orderId, "SKU-EXHAUSTED", 3, placedAt));
    OrderPlacedIntegrationEvent event = new OrderPlacedIntegrationEvent(
        UUID.randomUUID(), orderId, "SKU-EXHAUSTED", 3, placedAt);
    double metricBefore = exhaustedMetricCount();
    conflictInjector.failFirstAllocationAttempts(3);

    assertThatThrownBy(() -> consume(event))
        .isInstanceOf(AllocationConcurrencyExhaustedException.class);

    assertThat(conflictInjector.invocations()).isEqualTo(3);
    assertThat(orderRepository.findById(orderId)).hasValueSatisfying(order ->
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING));
    assertThat(stockPoolRepository.findById(stockPoolId)).hasValueSatisfying(pool ->
        assertThat(pool.getReservedQuantity()).isZero());
    assertThat(stockReservationRepository.findActiveByOrderId(orderId)).isEmpty();
    assertThat(inboxRepository.findById(event.getEventId())).isEmpty();
    assertThat(outboxRepository.count()).isZero();
    assertThat(exhaustedMetricCount()).isEqualTo(metricBefore + 1.0);
  }

  private void consume(OrderPlacedIntegrationEvent event) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
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
}
