# v3 SKU 分區 Single-Writer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓 `ordering.order-events` 的 Kafka partition key 可以在 `orderId`（v1，預設）跟
`sku`（v3）之間切換，並用同一支 k6 劇本各跑一次、產出真實的吞吐量與衝突率對比圖。

**Architecture:** 新增一個字串設定 `archone.allocation.partition-key-strategy`，
`OrderingDomainEventTranslator` 依這個值決定 outbox 事件的 `aggregateId` 傳
`orderId` 還是 `sku`；`OutboxAppender.append` 的 `aggregateId` 參數型別從 `UUID`
放寬成 `String`（DB 欄位本來就是 `VARCHAR`，只是 Java 方法簽章目前限制成
`UUID`）；`e2e/perf/run.sh` 加一個環境變數把設定傳給 app；新增一支 Python script
讀兩輪 k6 結果畫對比圖。

**Tech Stack:** Java 25 / Spring Boot 4.0.7 / Spring Kafka，Python 3 + matplotlib
（畫圖），k6（壓測，已存在）。

## Global Constraints

- 設計文件：`docs/superpowers/specs/2026-07-26-v3-single-writer-design.md`——每個
  task 的細節都要符合這份文件；有衝突以文件為準。
- Partition 數維持 4（`e2e/perf/docker-compose.yml` 的 `KAFKA_NUM_PARTITIONS=4`
  不變），確保 v1／v3 對比時基礎設施條件一致。
- 範圍只到「下單 vs 下單」的衝突；「下單 vs 補貨」跨 consumer group 的殘留對撞
  明確不在這次範圍內，`AllocationRetryExecutor`／`DefaultErrorHandler`／DLT 整套
  保留、不刪除、不精簡。
- 新增的程式碼註解（Java Javadoc、Python docstring/comment）一律用台灣中文；沿用
  既有 codebase 慣例（簡體字不可混入，見過去一次 `选擇` 誤植的教訓）。
- 每個 Java 改動 task 完成後都要跑
  `./gradlew :order-promising:compileJava :order-promising:test --no-daemon`
  確認全綠，才能進下一個 task。
- 每個 task 完成後單獨 commit，不要把多個 task 疊在同一個 commit。
- 設計文件把「要不要仿照 Demo-01 加一個確定性的 SIT 測試佐證 0 衝突」留給計畫階段
  決定：這次**不加**。理由：Task 6 的即時 k6 對比已經直接滿足驗收標準（真實數字，
  不是估算），額外的 SIT 測試要重建 `FirstWaveConflictSynchronizer` 那類 test-only
  同步屏障，複雜度不小，對這次「產出對比報告」的目標邊際效益低，YAGNI。之後如果
  要更嚴謹地佐證「結構上不可能衝突」而不只是「這次跑 0 次」，可以再另開一個計畫。

---

### Task 1: 把 `OutboxAppender.append` 的 `aggregateId` 參數從 `UUID` 放寬成 `String`

**Files:**
- Modify: `order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAppender.java`
- Modify: `order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java`
- Modify: `order-promising/src/main/java/com/flowzati/archone/allocation/application/event/translator/AllocationDomainEventTranslator.java`
- Test（不用改斷言，只需確認仍然編譯、仍然通過）: `order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java`

**Interfaces:**
- Produces: `OutboxAppender.append(IntegrationEvent event, String aggregateType, String aggregateId, String route, Instant occurredAt)`——後面所有 task 呼叫這個方法時，`aggregateId` 都傳 `String`。

這是型別放寬的純重構：行為完全不變（所有呼叫點目前都還是傳 `orderId.toString()`），
不需要新增測試案例，用既有測試套件當回歸驗證即可。

- [ ] **Step 1: 跑一次現有測試，確認目前是綠的基準線**

Run: `./gradlew :order-promising:test --no-daemon --rerun 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 修改 `OutboxAppender.append` 的參數型別**

編輯 `order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAppender.java`，
把：

```java
  public void append(
      IntegrationEvent event,
      String aggregateType,
      UUID aggregateId,
      String route,
      Instant occurredAt
  ) {
    try {
      outboxRepo.append(new Outbox(
          event.getEventId(),
          aggregateType,
          aggregateId.toString(),
          event.getClass().getSimpleName(),
          route,
          objectMapper.writeValueAsString(event),
          occurredAt
      ));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize integration event", exception);
    }
  }
```

改成：

```java
  public void append(
      IntegrationEvent event,
      String aggregateType,
      String aggregateId,
      String route,
      Instant occurredAt
  ) {
    try {
      outboxRepo.append(new Outbox(
          event.getEventId(),
          aggregateType,
          aggregateId,
          event.getClass().getSimpleName(),
          route,
          objectMapper.writeValueAsString(event),
          occurredAt
      ));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize integration event", exception);
    }
  }
```

同時移除檔案開頭已經用不到的 `import java.util.UUID;`。

- [ ] **Step 3: 更新 `OrderingDomainEventTranslator` 的兩個呼叫點**

編輯 `order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java`，
把 `translate(OrderPlaced event)` 裡的：

```java
        OutboxAggregateTypes.ORDER,
        event.orderId(),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.placedAt()
```

改成：

```java
        OutboxAggregateTypes.ORDER,
        event.orderId().toString(),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.placedAt()
```

`translate(OrderCancelled event)` 裡同樣把 `event.orderId()` 改成
`event.orderId().toString()`。

- [ ] **Step 4: 更新 `AllocationDomainEventTranslator` 的兩個呼叫點**

編輯 `order-promising/src/main/java/com/flowzati/archone/allocation/application/event/translator/AllocationDomainEventTranslator.java`，
`translate(OrderAllocationCompleted event)` 與 `translate(OrderBackordered event)`
裡的 `event.orderId()` 都改成 `event.orderId().toString()`（這兩個保持 orderId
當 key，不受這次 v3 範圍影響，只是型別要跟著新簽章調整）。

- [ ] **Step 5: 編譯並跑全部測試，確認沒有回歸**

Run: `./gradlew :order-promising:compileJava :order-promising:test --no-daemon --rerun 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`，`DomainEventTranslatorTest` 兩個既有測試維持通過
（`row.aggregateId()` 的斷言值不變，因為 `orderId.toString()` 產生的字串跟原本
`UUID.toString()` 的結果相同）。

- [ ] **Step 6: Commit**

```bash
git add order-promising/src/main/java/com/flowzati/archone/common/outbox/OutboxAppender.java \
  order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java \
  order-promising/src/main/java/com/flowzati/archone/allocation/application/event/translator/AllocationDomainEventTranslator.java
git commit -m "$(cat <<'EOF'
refactor: widen OutboxAppender aggregateId to String

event_outbox.aggregateid is already VARCHAR and was designed to
support non-UUID aggregate ids; only OutboxAppender.append's Java
signature artificially narrowed it to UUID. Widening it now so a
later change can pass a raw SKU string as the Kafka partition key
without a UUID-hashing detour.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `OrderCancelled` domain event 加 `sku` 欄位

**Files:**
- Modify: `order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java`
- Modify: `order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java:112`
- Test: `order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java:88`
- Test: `order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java:39`

**Interfaces:**
- Produces: `OrderCancelled(UUID orderId, String sku, Instant cancelledAt)`——Task 3
  的 `OrderingDomainEventTranslator.translate(OrderCancelled event)` 會呼叫
  `event.sku()`。

- [ ] **Step 1: 修改測試，先讓它們編譯失敗**

編輯 `order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java`
第 88 行，把：

```java
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderCancelled(orderId, cancelledAt));
```

改成：

```java
    assertThat(order.releaseDomainEvents()).containsExactly(
        new OrderCancelled(orderId, "SKU-1", cancelledAt));
```

（`pendingOrder()` helper 建立的訂單就是用 `"SKU-1"`，見同檔案第 180 行。）

編輯 `order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java`
第 39 行，把：

```java
    verify(publisher).publishEvent(new OrderCancelled(order.getId(), cancelledAt));
```

改成：

```java
    verify(publisher).publishEvent(new OrderCancelled(order.getId(), "SKU-1", cancelledAt));
```

（該測試第 31 行 `Order.place(UUID.randomUUID(), "SKU-1", 3, placedAt)` 用的也是
`"SKU-1"`。）

- [ ] **Step 2: 跑測試確認編譯失敗**

Run: `./gradlew :order-promising:compileTestJava --no-daemon 2>&1 | tail -20`
Expected: `FAILED`，錯誤訊息包含
`constructor OrderCancelled in record OrderCancelled cannot be applied to given types`
（因為 `OrderCancelled` 還是舊的兩參數版本，測試已經改成傳三個參數）。

- [ ] **Step 3: `OrderCancelled` record 加 `sku` 欄位**

編輯 `order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java`，
把：

```java
package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record OrderCancelled(
    UUID orderId,
    Instant cancelledAt
) implements DomainEvent {
}
```

改成：

```java
package com.flowzati.archone.ordering.domain.event;

import com.flowzati.archone.common.ddd.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record OrderCancelled(
    UUID orderId,
    String sku,
    Instant cancelledAt
) implements DomainEvent {
}
```

- [ ] **Step 4: `Order.cancel()` 帶入 sku**

編輯 `order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java`
第 112 行，把：

```java
    events.add(new OrderCancelled(id, cancelledAt));
```

改成：

```java
    events.add(new OrderCancelled(id, sku, cancelledAt));
```

- [ ] **Step 5: 跑測試確認通過**

Run: `./gradlew :order-promising:test --no-daemon --rerun 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add order-promising/src/main/java/com/flowzati/archone/ordering/domain/event/OrderCancelled.java \
  order-promising/src/main/java/com/flowzati/archone/ordering/domain/model/Order.java \
  order-promising/src/test/java/com/flowzati/archone/ordering/domain/model/OrderTest.java \
  order-promising/src/test/java/com/flowzati/archone/ordering/application/usecase/CancelOrderUsecaseTest.java
git commit -m "$(cat <<'EOF'
feat: carry sku on OrderCancelled domain event

ReleaseReservationUsecase touches the same StockPool row that
allocation does, so a future SKU-based partition key needs sku
available at OrderCancelled translation time too, not just on
OrderPlaced.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: partition-key-strategy 設定，接進 `OrderingDomainEventTranslator`

**Files:**
- Modify: `order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java`
- Test: `order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java`

**Interfaces:**
- Consumes: `OrderCancelled(UUID orderId, String sku, Instant cancelledAt)`（Task 2）、
  `OutboxAppender.append(IntegrationEvent, String, String, String, Instant)`（Task 1）。
- Produces: `OrderingDomainEventTranslator(OutboxAppender outboxAppender, String partitionKeyStrategy)`
  ——建構子新增第二個參數，Spring 執行時由
  `@Value("${archone.allocation.partition-key-strategy:order-id}")` 注入；單元
  測試可以直接傳字面值 `"order-id"`／`"sku"`。

- [ ] **Step 1: 在 `DomainEventTranslatorTest.java` 加新測試，先讓它們編譯失敗**

編輯 `order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java`。

先把既有的 `new OrderingDomainEventTranslator(appender)`（第 32 行）改成：

```java
    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(new OrderPlaced(orderId, "SKU-1", 3, occurredAt));
```

再新增 import：

```java
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
```

在檔案最後一個測試方法（`shouldTranslateCompletedAllocationWithReservationDetails`）
後面加三個新測試：

```java
  @Test
  @DisplayName("partition-key-strategy=sku 時，下單事件應以 SKU 當 aggregateId")
  void shouldUseSkuAsAggregateIdWhenPartitionKeyStrategyIsSku() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "sku")
        .translate(new OrderPlaced(orderId, "SKU-1", 3, occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().aggregateId()).isEqualTo("SKU-1");
  }

  @Test
  @DisplayName("預設策略下，取消事件應以 orderId 當 aggregateId")
  void shouldUseOrderIdAsAggregateIdForCancelledByDefault() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "order-id")
        .translate(new OrderCancelled(orderId, "SKU-1", occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().aggregateId()).isEqualTo(orderId.toString());
  }

  @Test
  @DisplayName("partition-key-strategy=sku 時，取消事件應以 SKU 當 aggregateId")
  void shouldUseSkuAsAggregateIdForCancelledWhenPartitionKeyStrategyIsSku() {
    OutboxRepo outboxRepo = mock(OutboxRepo.class);
    OutboxAppender appender = new OutboxAppender(outboxRepo, new ObjectMapper().findAndRegisterModules());
    UUID orderId = UUID.randomUUID();

    new OrderingDomainEventTranslator(appender, "sku")
        .translate(new OrderCancelled(orderId, "SKU-1", occurredAt));

    ArgumentCaptor<Outbox> outbox = ArgumentCaptor.forClass(Outbox.class);
    verify(outboxRepo).append(outbox.capture());
    assertThat(outbox.getValue().aggregateId()).isEqualTo("SKU-1");
  }
```

- [ ] **Step 2: 跑測試確認編譯失敗**

Run: `./gradlew :order-promising:compileTestJava --no-daemon 2>&1 | tail -20`
Expected: `FAILED`，錯誤訊息包含
`constructor OrderingDomainEventTranslator in class OrderingDomainEventTranslator cannot be applied to given types`
（建構子還只吃一個參數）。

- [ ] **Step 3: 修改 `OrderingDomainEventTranslator`**

把整個檔案內容改成：

```java
package com.flowzati.archone.ordering.application.event.translator;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.OutboxAppender;
import com.flowzati.archone.common.outbox.OutboxAggregateTypes;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderingDomainEventTranslator {

  private static final String SKU_STRATEGY = "sku";

  private final OutboxAppender outboxAppender;
  private final String partitionKeyStrategy;

  public OrderingDomainEventTranslator(
      OutboxAppender outboxAppender,
      @Value("${archone.allocation.partition-key-strategy:order-id}") String partitionKeyStrategy
  ) {
    this.outboxAppender = outboxAppender;
    this.partitionKeyStrategy = partitionKeyStrategy;
  }

  @EventListener
  public void translate(OrderPlaced event) {
    outboxAppender.append(
        new OrderPlacedIntegrationEvent(
            IdGenerator.nextId(), event.orderId(), event.sku(), event.quantity(), event.placedAt()),
        OutboxAggregateTypes.ORDER,
        aggregateId(event.orderId(), event.sku()),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.placedAt()
    );
  }

  @EventListener
  public void translate(OrderCancelled event) {
    outboxAppender.append(
        new OrderCancelledIntegrationEvent(IdGenerator.nextId(), event.orderId(), event.cancelledAt()),
        OutboxAggregateTypes.ORDER,
        aggregateId(event.orderId(), event.sku()),
        IntegrationEventTopics.ORDERING_ORDER_EVENTS_TOPIC,
        event.cancelledAt()
    );
  }

  /**
   * sku 策略下用 SKU 當 partition key，讓同一個 SKU 的事件全部收斂進同一個
   * partition（single-writer，見 docs/superpowers/specs/2026-07-26-v3-single-writer-design.md）；
   * 預設（或任何非 "sku" 的值）沿用 v1 的 orderId。
   */
  private String aggregateId(UUID orderId, String sku) {
    return SKU_STRATEGY.equals(partitionKeyStrategy) ? sku : orderId.toString();
  }
}
```

- [ ] **Step 4: 跑測試確認通過**

Run: `./gradlew :order-promising:test --no-daemon --rerun 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`，`DomainEventTranslatorTest` 全部 5 個測試（2 個既有
+ 3 個新增）通過。

- [ ] **Step 5: 跑 SIT 套件確認沒有回歸（這個改動影響 outbox 事件路徑，SIT 有覆蓋）**

Run: `./gradlew :order-promising:sit --no-daemon --rerun 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add order-promising/src/main/java/com/flowzati/archone/ordering/application/event/translator/OrderingDomainEventTranslator.java \
  order-promising/src/test/java/com/flowzati/archone/common/outbox/DomainEventTranslatorTest.java
git commit -m "$(cat <<'EOF'
feat: add archone.allocation.partition-key-strategy config

OrderingDomainEventTranslator now picks orderId (default) or sku as
the outbox aggregateId (Kafka partition key) based on this property.
sku strategy makes same-SKU order-placement events land on the same
Kafka partition, which Kafka's per-partition consumer exclusivity
turns into single-writer processing for that SKU.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `run.sh` 支援 `PARTITION_KEY_STRATEGY`

**Files:**
- Modify: `e2e/perf/run.sh`

**Interfaces:**
- Consumes: `archone.allocation.partition-key-strategy`（Task 3 新增的設定）。

- [ ] **Step 1: 修改 `cmd_up`**

編輯 `e2e/perf/run.sh` 的 `cmd_up()` 函式，把：

```bash
cmd_up() {
  set -e
  local sku="${SKU:-HOT-SKU}"
  local stock="${STOCK:-500}"
  local vus="${VUS:-1000}"
  local results_file="${RESULTS_FILE:-${ROOT_DIR}/k6/results/hot-sku-burst-$(date +%Y%m%dT%H%M%S).json}"
```

改成：

```bash
cmd_up() {
  set -e
  local sku="${SKU:-HOT-SKU}"
  local stock="${STOCK:-500}"
  local vus="${VUS:-1000}"
  local partition_key_strategy="${PARTITION_KEY_STRATEGY:-order-id}"
  local results_file="${RESULTS_FILE:-${ROOT_DIR}/k6/results/hot-sku-burst-$(date +%Y%m%dT%H%M%S).json}"
```

再把同一個函式裡啟動 app 的區塊：

```bash
    echo "啟動 app，log 寫到 ${APP_LOG}"
    (cd "${REPO_ROOT}" && nohup ./gradlew :order-promising:bootRun \
      --args='--spring.profiles.active=dev --spring.kafka.listener.concurrency=4 --management.endpoints.web.exposure.include=prometheus,health' \
      > "${APP_LOG}" 2>&1 &)
```

改成（注意 `--args` 從單引號改雙引號，因為現在裡面有變數要展開）：

```bash
    echo "啟動 app（partition-key-strategy=${partition_key_strategy}），log 寫到 ${APP_LOG}"
    (cd "${REPO_ROOT}" && nohup ./gradlew :order-promising:bootRun \
      --args="--spring.profiles.active=dev --spring.kafka.listener.concurrency=4 --management.endpoints.web.exposure.include=prometheus,health --archone.allocation.partition-key-strategy=${partition_key_strategy}" \
      > "${APP_LOG}" 2>&1 &)
```

檔案開頭的用法註解也順手補一行說明（在 `#   ./e2e/perf/run.sh down` 那行後面加）：

```bash
#   PARTITION_KEY_STRATEGY=sku SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh up
#                                                 v3：SKU 分區 single-writer
```

- [ ] **Step 2: 語法檢查**

Run: `bash -n e2e/perf/run.sh && echo ok`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add e2e/perf/run.sh
git commit -m "$(cat <<'EOF'
feat: let run.sh switch partition-key-strategy for v3

PARTITION_KEY_STRATEGY env var (default order-id, matching current
behavior) forwards to the app as
--archone.allocation.partition-key-strategy so the same run.sh drives
both the v1 and v3 comparison runs.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `plot_comparison.py`（v1 vs v3 對比圖）

**Files:**
- Create: `e2e/perf/k6/plot_comparison.py`

**Interfaces:**
- Consumes：k6 `--summary-export` JSON（`metrics.iterations.rate` 當吞吐量、
  `metrics.order_decision_latency_ms` 可選顯示）；命令列傳入的
  attempts／exhausted 整數。
- Produces：兩個 PNG 檔（`throughput_comparison.png`、`conflict_comparison.png`），
  存進呼叫者指定的輸出目錄。

- [ ] **Step 1: 寫 script**

建立 `e2e/perf/k6/plot_comparison.py`：

```python
#!/usr/bin/env python3
"""v1（樂觀鎖）vs v3（SKU 分區 single-writer）壓測對比圖。

讀兩份 k6 --summary-export 的 JSON（拿吞吐量），加上命令列傳入的衝突計數
（跑完 ./e2e/perf/run.sh verify <SKU> 之後手動抄過來的
order_allocation_retry_attempts_total / _exhausted_total），畫兩張長條圖：
吞吐量對比、衝突次數對比。

用法：
  python3 plot_comparison.py \
    --v1-summary hot-sku-burst-v1.json --v1-attempts 41 --v1-exhausted 10 \
    --v3-summary hot-sku-burst-v3.json --v3-attempts 0  --v3-exhausted 0
"""

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt


def load_iterations_rate(summary_path: str) -> float:
    """從 k6 summary JSON 讀出 iterations.rate（每秒完成幾張訂單）。"""
    with open(summary_path, encoding="utf-8") as f:
        data = json.load(f)
    return data["metrics"]["iterations"]["rate"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-summary", required=True, help="v1（orderId 分區）的 k6 summary JSON 路徑")
    parser.add_argument("--v1-attempts", required=True, type=int, help="v1 的 order_allocation_retry_attempts_total")
    parser.add_argument("--v1-exhausted", required=True, type=int, help="v1 的 order_allocation_retry_exhausted_total")
    parser.add_argument("--v3-summary", required=True, help="v3（sku 分區）的 k6 summary JSON 路徑")
    parser.add_argument("--v3-attempts", required=True, type=int, help="v3 的 order_allocation_retry_attempts_total")
    parser.add_argument("--v3-exhausted", required=True, type=int, help="v3 的 order_allocation_retry_exhausted_total")
    parser.add_argument(
        "--output-dir",
        default=".",
        help="輸出 PNG 的資料夾，預設當前目錄",
    )
    return parser.parse_args()


def plot_throughput(v1_rate: float, v3_rate: float, output_dir: Path) -> None:
    """畫吞吐量（iterations/s，也就是每秒完成幾張訂單）對比長條圖。"""
    fig, ax = plt.subplots(figsize=(6, 4))
    labels = ["v1（orderId 分區）", "v3（sku 分區 single-writer）"]
    values = [v1_rate, v3_rate]
    bars = ax.bar(labels, values, color=["#4C72B0", "#55A868"])
    ax.set_ylabel("吞吐量（訂單／秒）")
    ax.set_title("v1 vs v3 吞吐量對比")
    for bar, value in zip(bars, values):
        ax.annotate(
            f"{value:.1f}",
            xy=(bar.get_x() + bar.get_width() / 2, bar.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
        )
    fig.tight_layout()
    fig.savefig(output_dir / "throughput_comparison.png", dpi=150)
    plt.close(fig)


def plot_conflicts(
    v1_attempts: int, v1_exhausted: int, v3_attempts: int, v3_exhausted: int, output_dir: Path
) -> None:
    """畫衝突次數（重試次數／重試用盡次數）對比長條圖。"""
    fig, ax = plt.subplots(figsize=(6, 4))
    labels = ["v1（orderId 分區）", "v3（sku 分區 single-writer）"]
    attempts = [v1_attempts, v3_attempts]
    exhausted = [v1_exhausted, v3_exhausted]
    x = range(len(labels))
    width = 0.35
    ax.bar([i - width / 2 for i in x], attempts, width, label="重試次數", color="#C44E52")
    ax.bar([i + width / 2 for i in x], exhausted, width, label="重試用盡次數", color="#8172B2")
    ax.set_xticks(list(x))
    ax.set_xticklabels(labels)
    ax.set_ylabel("次數")
    ax.set_title("v1 vs v3 衝突率對比")
    ax.legend()
    fig.tight_layout()
    fig.savefig(output_dir / "conflict_comparison.png", dpi=150)
    plt.close(fig)


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    v1_rate = load_iterations_rate(args.v1_summary)
    v3_rate = load_iterations_rate(args.v3_summary)
    plot_throughput(v1_rate, v3_rate, output_dir)
    plot_conflicts(args.v1_attempts, args.v1_exhausted, args.v3_attempts, args.v3_exhausted, output_dir)

    print(f"已輸出 {output_dir / 'throughput_comparison.png'}")
    print(f"已輸出 {output_dir / 'conflict_comparison.png'}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 用既有的 v1 baseline JSON 跑一次，確認 script 本身沒問題**

Run:
```bash
python3 e2e/perf/k6/plot_comparison.py \
  --v1-summary e2e/perf/k6/results/hot-sku-burst-v1.json --v1-attempts 41 --v1-exhausted 10 \
  --v3-summary e2e/perf/k6/results/hot-sku-burst-v1.json --v3-attempts 0 --v3-exhausted 0 \
  --output-dir /tmp/plot-smoke-test
ls /tmp/plot-smoke-test
```

Expected: 印出兩行「已輸出 ...」，`ls` 顯示
`conflict_comparison.png  throughput_comparison.png` 兩個檔案都存在且非 0 bytes
（這裡故意把 v1、v3 都指向同一份既有 JSON 只是為了驗證 script 能跑、能讀欄位、
能畫圖，不是真的對比數字——真正的 v3 資料要等 Task 6 才有）。

Run: `rm -rf /tmp/plot-smoke-test`（清掉這次煙霧測試的暫存輸出）

- [ ] **Step 3: Commit**

```bash
git add e2e/perf/k6/plot_comparison.py
git commit -m "$(cat <<'EOF'
feat: add v1 vs v3 comparison chart script

Reads two k6 --summary-export JSONs plus manually-recorded
Prometheus retry counts, plots throughput and conflict-rate
comparison bar charts. Run manually after both run.sh up rounds
(v1 default, v3 with PARTITION_KEY_STRATEGY=sku) complete.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 真的跑 v1／v3 對比、產出兩張圖、更新 README

**Files:**
- Modify: `e2e/perf/README.md`
- Produces（不進 git 版控以外的規劃，但要存在 repo 裡）: `e2e/perf/k6/results/hot-sku-burst-orderid-key.json`、`e2e/perf/k6/results/hot-sku-burst-sku-key.json`、`e2e/perf/k6/results/throughput_comparison.png`、`e2e/perf/k6/results/conflict_comparison.png`

這個 task 是整個計畫的核心交付：真的跑出兩輪數字、真的畫出兩張圖，不是估算。
前置條件：docker、OrbStack（或其他相容的 Docker runtime）跑著、`k6`／`python3`／
`matplotlib` 都已安裝（Task 5 Step 2 已經驗證過 `matplotlib` 可用）。

- [ ] **Step 1: 確保環境乾淨，跑 v1（orderId 分區，預設）**

```bash
./e2e/perf/run.sh down
RESULTS_FILE=e2e/perf/k6/results/hot-sku-burst-orderid-key.json \
  SKU=HOT-SKU STOCK=500 VUS=1000 ./e2e/perf/run.sh up
```

Expected: 最後印出 `結果存到 e2e/perf/k6/results/hot-sku-burst-orderid-key.json`，
exit code `0`（k6 thresholds 全過：500 ALLOCATED／500 BACKORDERED、無逾時）。

- [ ] **Step 2: 記錄 v1 的衝突數字**

```bash
./e2e/perf/run.sh verify HOT-SKU
```

把輸出裡 `order_allocation_retry_attempts_total{operation="allocate-order"}` 跟
`order_allocation_retry_exhausted_total{operation="allocate-order"}` 這兩個數字
記下來（後面 Step 5 要用）。

- [ ] **Step 3: 關掉、換 v3（sku 分區）重跑同一劇本**

```bash
./e2e/perf/run.sh down
RESULTS_FILE=e2e/perf/k6/results/hot-sku-burst-sku-key.json \
  SKU=HOT-SKU STOCK=500 VUS=1000 PARTITION_KEY_STRATEGY=sku ./e2e/perf/run.sh up
```

Expected: 最後印出 `結果存到 e2e/perf/k6/results/hot-sku-burst-sku-key.json`，
exit code `0`。

- [ ] **Step 4: 記錄 v3 的衝突數字**

```bash
./e2e/perf/run.sh verify HOT-SKU
```

同樣記下兩個數字——理論上 `attempts_total` 應該是 0（single-writer 下不會有
optimistic-lock 衝突），如果不是 0，先不要繼續往下做，回頭檢查 Task 3 的
partition-key-strategy 是不是真的生效（用
`docker logs order-promising-e2e-perf-kafka-connect-1` 或
partition assignment log 確認同一個 SKU 的事件真的分到同一個 partition）。

- [ ] **Step 5: 畫對比圖**

用 Step 2、Step 4 記下來的四個數字，取代下面的 `<...>`：

```bash
python3 e2e/perf/k6/plot_comparison.py \
  --v1-summary e2e/perf/k6/results/hot-sku-burst-orderid-key.json \
  --v1-attempts <v1 attempts> --v1-exhausted <v1 exhausted> \
  --v3-summary e2e/perf/k6/results/hot-sku-burst-sku-key.json \
  --v3-attempts <v3 attempts> --v3-exhausted <v3 exhausted> \
  --output-dir e2e/perf/k6/results
```

Expected: `e2e/perf/k6/results/throughput_comparison.png` 與
`e2e/perf/k6/results/conflict_comparison.png` 都產生，非 0 bytes。

- [ ] **Step 6: 關閉環境**

```bash
./e2e/perf/run.sh down
```

- [ ] **Step 7: 把真實數字寫進 README**

編輯 `e2e/perf/README.md`，在「現況」段落最後面（`如果訊息卡在退避重送中...`
那段之前）加一個新的小節，把 Step 1～5 實際跑出來的數字填進去（不是這裡先寫死
的範例數字，要用真正跑出來的）：

```markdown
### v3：SKU 分區 single-writer 對比（`archone.allocation.partition-key-strategy=sku`）

同一劇本（1,000 VUs／庫存 500）分別跑 v1（`orderId` 分區）與 v3（`sku`
分區）：

| 情境 | 吞吐量（訂單／秒） | 重試次數 | 重試用盡次數 |
| --- | --- | --- | --- |
| v1（orderId 分區） | <填入 Step 1 的 iterations rate> | <v1 attempts> | <v1 exhausted> |
| v3（sku 分區） | <填入 Step 3 的 iterations rate> | <v3 attempts> | <v3 exhausted> |

對比圖：[`throughput_comparison.png`](k6/results/throughput_comparison.png)、
[`conflict_comparison.png`](k6/results/conflict_comparison.png)。

v3 只解決「下單 vs 下單」的衝突；「下單 vs 補貨」跨 consumer group 的殘留對撞
不在這次範圍內，見
[`docs/superpowers/specs/2026-07-26-v3-single-writer-design.md`](../../docs/superpowers/specs/2026-07-26-v3-single-writer-design.md)。
```

（iterations rate 可以直接從 Step 1/3 的 k6 終端輸出「CUSTOM」區塊上面的
`iterations` 那行讀，或用
`python3 -c "import json; print(json.load(open('e2e/perf/k6/results/hot-sku-burst-orderid-key.json'))['metrics']['iterations']['rate'])"`
查。）

- [ ] **Step 8: Commit**

```bash
git add e2e/perf/README.md e2e/perf/k6/results/hot-sku-burst-orderid-key.json \
  e2e/perf/k6/results/hot-sku-burst-sku-key.json \
  e2e/perf/k6/results/throughput_comparison.png \
  e2e/perf/k6/results/conflict_comparison.png
git commit -m "$(cat <<'EOF'
test: record v1 vs v3 comparison results

Real k6 runs (not estimated) for orderId-partitioned (v1) vs
sku-partitioned (v3) allocation, same 1,000-VU hot-SKU scenario.
Charts and raw k6 summaries committed alongside the README writeup.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
