package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.spring.optimisticlocking.OptimisticLockingRetryExhaustedException;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.SitDatabase;
import com.flowzati.archone.testsupport.OrderFixtures;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo-01：1,000 張同 SKU 訂單競爭 10 件庫存的熱門 SKU 併發劇本。
 *
 * <p>驗證範圍：在既有的 datasource connection pool、Inbox/Outbox 與三次重試的 optimistic-lock
 * 機制下，1,000 筆併發送出的 OrderPlaced 事件最終都會收斂為正確的 ALLOCATED／BACKORDERED
 * 結果，且不超賣、不遺失事件、不重複 reservation。這是 bounded database concurrency 下的
 * submission burst 展示，不是 production throughput/latency benchmark，也不啟動 Kafka broker。
 */
@SpringBootTest(
    classes = ArchoneApplication.class,
    properties = "spring.kafka.listener.auto-startup=false",
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import({
    PostgreSQLTestConfiguration.class,
    AllocationHotSkuConcurrencyIntegrationTest.FirstWaveConflictConfiguration.class
})
class AllocationHotSkuConcurrencyIntegrationTest {

  private static final String HOT_SKU = "HOT-SKU";
  private static final int TOTAL_ORDERS = 1_000;
  private static final int ON_HAND_QUANTITY = 10;
  private static final int MAX_RECOVERY_ROUNDS = 5;

  @org.springframework.beans.factory.annotation.Autowired
  private com.flowzati.archone.testsupport.AllocationOutcomeDrainFactory outcomeDrainFactory;

  @Autowired
  private com.flowzati.archone.testsupport.AllocationOrderLifecycleEventDriver consumer;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private StockQuantRepository stockQuantRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private FirstWaveConflictSynchronizer conflictSynchronizer;

  @AfterEach
  void clearDatabase() {
    conflictSynchronizer.reset();
    SitDatabase.clear(jdbcTemplate);
  }

  /** 訂單行的 (owner_id, sku_code) 有外鍵指向主檔,寫入訂單前主檔必須先存在。 */
  @BeforeEach
  void seedCatalogForOrders() {
    OrderFixtures.seedCatalog(jdbcTemplate, OrderFixtures.OWNER_ID, "HOT-SKU");
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  @DisplayName("1,000 張訂單競爭 10 件同 SKU 庫存時應不超賣且最終全部收斂")
  void shouldReconcileAllOrdersWithoutOversellingUnderHotSkuContention() throws Exception {
    // Step 1：準備 fixture —— 一個只有 10 件庫存的 StockQuant，以及 1,000 張各要 1 件的 PENDING
    // Order／OrderPlaced event。每筆 event 有自己的 eventId，之後可以個別重送。
    UUID stockQuantId = UUID.randomUUID();
    Instant receivedAt = Instant.now().minusSeconds(1);
    stockQuantRepository.save(StockFixtures.unexpiredBatch(stockQuantId, HOT_SKU, ON_HAND_QUANTITY, 0));

    List<OrderPlacedIntegrationEvent> events = new ArrayList<>(TOTAL_ORDERS);
    for (int i = 0; i < TOTAL_ORDERS; i++) {
      UUID orderId = IdGenerator.nextId();
      orderRepository.save(OrderFixtures.pendingOrder(orderId, HOT_SKU, 1, receivedAt));
      events.add(new OrderPlacedIntegrationEvent(UUID.randomUUID(), orderId, receivedAt));
    }

    // Step 2：把 1,000 筆事件同時丟進 allocation entrypoint。這一步只保證「同時送出」，
    // 實際同時跑幾個 transaction 由 datasource connection pool（預設 10 條連線）決定。
    // 少數幾筆會在自己的 3 次內建 retry 都遇到衝突而耗盡，回傳值就是這些需要重送的原始事件。
    List<OrderPlacedIntegrationEvent> exhaustedAfterWave = submitConcurrentWave(events);

    // Step 3：這一波送完之後，衝突高峰已經過去，此時逐筆重送 exhausted 的事件通常一次就會成功
    // （模擬 Kafka 的 at-least-once redelivery）。若仍收斂不了才視為測試失敗。
    redeliverUntilConverged(exhaustedAfterWave);

    // Step 4：佐證「真的發生過至少一次 optimistic-lock conflict」。只有 10 筆可成功進入
    // commit；committer 呼叫次數超過 10 代表至少一個 stale plan 曾與 winner 競爭後重試。
    // FirstWaveConflictSynchronizer 已經讓最先抵達的兩筆一定會撞在一起，所以這個斷言必過。
    assertThat(conflictSynchronizer.invocations())
        .as("至少一次重試代表 first-wave synchronization gate 觸發了真實的 optimistic-lock conflict")
        .isGreaterThan(ON_HAND_QUANTITY);

    // Step 5：所有事件都已經有確定結果，對帳持久化狀態，確認沒有超賣、遺失事件或重複 reservation。
    assertReconciledState(stockQuantId);
  }

  /** 從同一個 start gate 釋放 bounded worker 任務，回傳耗盡重試的原始事件供重送。 */
  private List<OrderPlacedIntegrationEvent> submitConcurrentWave(
      List<OrderPlacedIntegrationEvent> events) throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    // Worker 數對齊 test datasource 的預設連線池上限；其餘 submission 留在 executor queue，
    // 避免 1,000 個 virtual threads 同時在 Hikari acquisition timeout 上排隊，讓測試測到
    // allocation contention 而不是 connection-pool admission timeout。
    ExecutorService executor = Executors.newFixedThreadPool(10);
    try {
      // 先把 1,000 個任務全部排進去；首批 worker 卡在 startGate，後續留在 executor queue。
      List<CompletableFuture<OrderPlacedIntegrationEvent>> futures = events.stream()
          .map(event -> CompletableFuture.supplyAsync(
              () -> deliverAwaitingGate(event, startGate), executor))
          .toList();

      // 全部排隊完成後才一次放行，這樣 1,000 個任務會盡量同時開始搶同一個 StockQuant，
      // 而不是照建立順序一個一個依序送出。
      startGate.countDown();

      // 等整批送完；120 秒是留給「10 條連線處理 1,000 筆 transaction＋少量 retry」的寬鬆上限，
      // 若逾時代表這批送出卡住了，直接讓測試失敗並回報。
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
          .get(120, TimeUnit.SECONDS);

      // 收集「重試三次都還是衝突」的事件；其餘任何非預期例外已經在 allOf(...).get() 這一步
      // 就會以 ExecutionException 往外拋出，讓測試立即失敗（design 要求的
      // 「非 retry-exhaustion 的失敗要讓劇本立刻失敗」）。
      List<OrderPlacedIntegrationEvent> exhausted = new ArrayList<>();
      for (CompletableFuture<OrderPlacedIntegrationEvent> future : futures) {
        OrderPlacedIntegrationEvent exhaustedEvent = future.join();
        if (exhaustedEvent != null) {
          exhausted.add(exhaustedEvent);
        }
      }
      return exhausted;
    } finally {
      executor.shutdownNow();
    }
  }

  /** 等待 start gate 後送出一筆事件；回傳非 null 代表該筆重試耗盡，其餘失敗直接往外拋出使測試失敗。 */
  private OrderPlacedIntegrationEvent deliverAwaitingGate(
      OrderPlacedIntegrationEvent event, CountDownLatch startGate) {
    try {
      startGate.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the start gate", interrupted);
    }
    try {
      // typed consumer chain 內部已經包了三次重試（初始呼叫＋兩次 retry）；
      // 只有三次都遇到 optimistic-lock conflict 才會冒出通用的 retry-exhausted 例外。
      consume(event);
      return null;
    } catch (OptimisticLockingRetryExhaustedException exhausted) {
      return event;
    }
  }

  /** 只重送 retry-exhausted 的原始事件，直到全數收斂或達到 bounded recovery limit。 */
  private void redeliverUntilConverged(List<OrderPlacedIntegrationEvent> exhaustedEvents) {
    List<OrderPlacedIntegrationEvent> pending = exhaustedEvents;
    int round = 0;
    // 併發波次已經結束，這裡是單執行緒依序重送，理論上第一輪就會收斂；
    // 保留多輪、有上限的迴圈只是為了不讓極端情況卡成無窮迴圈。
    while (!pending.isEmpty() && round < MAX_RECOVERY_ROUNDS) {
      List<OrderPlacedIntegrationEvent> stillExhausted = new ArrayList<>();
      for (OrderPlacedIntegrationEvent event : pending) {
        try {
          // 用同一個 eventId 重送，模擬 Kafka at-least-once redelivery：
          // 原本失敗的那次 transaction（含 Inbox claim）已經整個 rollback，
          // 所以這次重送會被 Inbox 當成全新事件正常處理。
          consume(event);
        } catch (OptimisticLockingRetryExhaustedException exhausted) {
          stillExhausted.add(event);
        }
      }
      pending = stillExhausted;
      round++;
    }

    assertThat(pending)
        .as("Retry-exhausted deliveries remaining after %d bounded recovery rounds", MAX_RECOVERY_ROUNDS)
        .isEmpty();
  }

  private void assertReconciledState(UUID stockQuantId) {
    // 配貨只寫自己的表並發事件；訂單狀態由 ordering 收到那則事件後才推進。SIT 沒有
    // Debezium，所以先自己把 outbox 的配貨結果餵回去——production 裡是 Kafka 做這件事。
    outcomeDrain().drain();

    // 1) Order 結果：10 件庫存只夠 10 張訂單成功，其餘 990 張應該進 BACKORDERED，不能有第三種狀態
    //    或有訂單卡在 PENDING（代表事件遺失或漏處理）。
    Integer allocatedCount = jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'ALLOCATED'
        """, Integer.class, HOT_SKU);
    Integer backorderedCount = jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM orders o
        JOIN order_lines l ON l.order_id = o.id
        WHERE l.sku_code = ? AND o.status = 'PENDING'
        """, Integer.class, HOT_SKU);
    assertThat(allocatedCount).isEqualTo(ON_HAND_QUANTITY);
    assertThat(backorderedCount).isEqualTo(TOTAL_ORDERS - ON_HAND_QUANTITY);

    // 2) 鎖定結果：明細筆數、總量都要精確等於庫存數，且 order_id 不重複——
    //    這是直接偵測「超賣」與「同一張訂單被重複建立 reservation」的斷言。
    // 「還有效」不再是一個狀態欄位——**明細存在就代表鎖著**，釋放是刪除那一列。因此三個
    // 查詢都不帶條件；少了那個 WHERE 正是這次遷移在這裡的全部內容。
    //
    // 區域變數仍叫 reservation：命名收斂集中在第四個 change，這裡動它會讓「斷言一字未改」
    // 這件事變得難以核對。
    // **只數為需求鎖住的那些明細。** 入庫走搬運之後，stock_move_lines 同時是收貨的紀錄——
    // 不篩的話補進來的每一批都會被算成一筆預留。判準是「這條明細背後有訂單行」，那正是
    // 「為某張單鎖的」的定義；收貨的搬運沒有訂單行。
    Integer activeReservationCount = jdbcTemplate.queryForObject("""
        SELECT count(*)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
         WHERE m.order_line_id IS NOT NULL
        """, Integer.class);
    Integer activeReservationQuantity = jdbcTemplate.queryForObject("""
        SELECT coalesce(sum(ml.quantity), 0)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
         WHERE m.order_line_id IS NOT NULL
        """, Integer.class);
    Integer distinctReservedOrders = jdbcTemplate.queryForObject("""
        SELECT count(DISTINCT m.order_line_id)
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
        """, Integer.class);
    assertThat(activeReservationCount).isEqualTo(ON_HAND_QUANTITY);
    assertThat(activeReservationQuantity).isEqualTo(ON_HAND_QUANTITY);
    assertThat(distinctReservedOrders).isEqualTo(ON_HAND_QUANTITY);

    // 3a) **庫存必須集中在單一批次。**
    //
    // 這是分批之後最重要的一條，而它守的不是正確性、是**測試本身還有沒有在測東西**：
    // 這支測試的價值全在「1,000 張單真的搶同一列」所產生的樂觀鎖衝突。庫存若散成三批，
    // 衝突就分散了，競爭強度完全不同，而上面每一條斷言仍然會通過——那是最糟的失敗方式，
    // 因為測試是綠的，測到的東西卻不見了。
    Integer batchCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM stock_pools WHERE sku_code = ?", Integer.class, HOT_SKU);
    assertThat(batchCount)
        .withFailMessage("熱點庫存必須只有一列，實際有 %d 列——競爭已被分散，這支測試不再測到"
            + "真實的樂觀鎖衝突", batchCount)
        .isEqualTo(1);

    // 3b) StockQuant 結果：on-hand 不變、reserved 等於庫存、ATP 歸零——三個數字彼此要一致。
    assertThat(stockQuantRepository.findById(stockQuantId)).hasValueSatisfying(pool -> {
      assertThat(pool.getOnHandQuantity()).isEqualTo(ON_HAND_QUANTITY);
      assertThat(pool.getReservedQuantity()).isEqualTo(ON_HAND_QUANTITY);
      assertThat(pool.availableToPromise()).isZero();
    });

    // 4) Inbox 結果：1,000 個下單事件都要有一筆 claim，代表沒有事件被靜默遺失，
    //    也沒有殘留任何「rollback 後沒被成功重送」的半途狀態。
    //
    //    **以事件型別篩選，不數總筆數。** 現在有兩個 context 各自去重：allocation 消費
    //    OrderPlaced，ordering 只消費成功配貨結果（OrderAllocated）。缺貨的需求留在
    //    StockMove.CONFIRMED，不再產生一個重複的訂單事件。
    Integer inboxCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_inbox WHERE event_type = ?",
        Integer.class, OrderPlacedIntegrationEvent.EVENT_TYPE);
    assertThat(inboxCount).isEqualTo(TOTAL_ORDERS);

    //    ordering 側也該收齊：每張單一則結果事件，一則都不能少。
    Integer orderingClaims = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_inbox WHERE event_type = ?",
        Integer.class,
        OrderAllocatedIntegrationEvent.EVENT_TYPE);
    assertThat(orderingClaims).isEqualTo(ON_HAND_QUANTITY);

    // 5) 每張結果都有 lifecycle event；成功配置另外有一筆 WMS handoff snapshot。
    Integer outboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM event_outbox", Integer.class);
    Integer allocatedOutboxCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM event_outbox WHERE type = ?", Integer.class,
        OrderAllocatedIntegrationEvent.EVENT_TYPE);
    assertThat(outboxCount).isEqualTo(ON_HAND_QUANTITY * 2);
    assertThat(allocatedOutboxCount).isEqualTo(ON_HAND_QUANTITY);
  }

  private void consume(OrderPlacedIntegrationEvent event) {
    consumer.consume(event);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FirstWaveConflictConfiguration {

    @Bean
    FirstWaveConflictSynchronizer firstWaveConflictSynchronizer() {
      return new FirstWaveConflictSynchronizer();
    }
  }

  /**
   * Test-only interceptor：讓最先抵達的兩個 allocation attempt 在都讀到同一版 StockQuant 後
   * 才同時釋放，逼出一次真實的 JPA optimistic-lock conflict，而不是注入合成例外。
   */
  @Aspect
  static class FirstWaveConflictSynchronizer {

    private final AtomicInteger invocations = new AtomicInteger();
    private volatile CountDownLatch firstWaveGate = new CountDownLatch(2);

    // 這個 pointcut 卡在 committer 的「呼叫當下」，此時 coordinator 已在同一個 transaction
    // 裡讀好了 StockQuant；只要最先抵達的兩個 attempt 都卡在這裡，
    // 就代表兩邊都是讀到同一個已提交版本的 StockQuant，之後放行時必定有一邊會在真正 flush／
    // commit 時因為 @Version 不符而被 JPA 拒絕——這就是「真實」而非「合成」的 conflict。
    // 切在 planner 與持久化 commit 之間；往外移到 usecase 就會落在 stock read 之前，往內移到
    // AllocationDemandPlanner 則碰不到持久化。
    //
    // **切點是字串，指錯不會編譯失敗，只會靜默匹配不到任何東西**——那時每一條斷言都仍然
    // 執行，只是重試次數變成 0。元件改名或搬家時，這一行必須跟著改。
    @Around("execution(* com.flowzati.archone.inventory.allocation.application.service.reservation."
        + "AllocationCommitter.commit(..))")
    public Object synchronizeFirstWave(ProceedingJoinPoint joinPoint) throws Throwable {
      int invocation = invocations.incrementAndGet();
      // 只有可行 plan 才會進 committer。第 1、2 次呼叫被攔下來同步，第 3 次以後直接放行，
      // 避免把整批競爭者
      // 都卡在同一個屏障上而在 connection pool 後面死鎖。
      if (invocation <= 2) {
        CountDownLatch gate = firstWaveGate;
        gate.countDown();
        if (!gate.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("First-wave allocation attempts did not reach the barrier");
        }
      }
      return joinPoint.proceed();
    }

    int invocations() {
      return invocations.get();
    }

    void reset() {
      invocations.set(0);
      firstWaveGate = new CountDownLatch(2);
    }
  }

  private com.flowzati.archone.testsupport.AllocationOutcomeDrain outcomeDrain() {
    return outcomeDrainFactory.create();
  }
}
