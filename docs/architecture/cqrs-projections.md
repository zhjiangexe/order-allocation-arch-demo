# 簡明理解 CQRS Projection

`Projection` 最簡單的意思是：

> 把原始資料轉換成適合某種查詢的形狀。

假設訂單履約畫面需要以下資料：

```json
{
  "orderId": "O-1001",
  "orderStatus": "CONFIRMED",
  "allocationStatus": "COMPLETED",
  "shipmentStatus": "PICKING"
}
```

但這些資料分散在 Ordering、Inventory、WMS 和 Fulfillment Workflow。系統必須用某種方式將它們投影成
`OrderFulfillmentView`。這個轉換過程及其結果，就是 Projection。

Projection 不一定需要新資料表、不一定使用事件，也不等於把資料放在 JVM 記憶體。

## 做法一：查詢當下轉換

最簡單的 Projection 是查詢時才組裝：

```java
Order order = orderRepository.findById(orderId);
return new OrderView(order.id(), order.status(), order.totalAmount());
```

也可以直接使用 SQL，只選取 View 需要的欄位：

```sql
SELECT id, status, total_amount
FROM orders
WHERE id = :orderId;
```

這裡的 `orders 資料列 → OrderView` 就是一種 Projection。

特性：

- 不需要額外資料表。
- 不會長期占用 JVM 記憶體。
- 資料永遠接近最新狀態。
- 每次查詢都要重新計算或 JOIN。
- 查詢複雜時可能變慢。

這種方式適合資料位於同一個資料庫、JOIN 不複雜，而且必須看到最新資料的查詢。例如訂單列表可以直接查成
`OrderSummaryView`，不需要建立另一張 Projection table。

### 初始化

不需要初始化 Projection 資料。每次查詢都直接從目前的來源資料計算結果。

應用程式仍然需要用 migration 建立 `orders` 等來源資料表，但不需要額外執行 backfill、事件 replay 或建立
Projection table。既有資料與新資料會自然出現在查詢結果中。

## 做法二：Database View

可以把常用 SQL 保存成資料庫 View：

```sql
CREATE VIEW order_summary_view AS
SELECT
    o.id,
    o.status,
    o.total_amount,
    c.name AS customer_name
FROM orders o
JOIN customers c ON c.id = o.customer_id;
```

Database View 本質上通常只是「被命名的 SQL」。

特性：

- 通常不複製資料。
- 不需要應用程式維護同步。
- 每次查詢仍可能執行原本的 JOIN。
- 可以簡化應用程式的查詢程式碼。
- 效能不一定比直接執行 SQL 更好。

一般 View 幾乎不增加資料儲存空間，也不會把所有結果放進 JVM 記憶體。資料庫執行查詢時仍會正常使用查詢記憶體與
cache。

### 初始化

需要初始化 View 的結構，但不需要初始化 View 的資料。通常在 database migration 中執行 `CREATE VIEW`：

```sql
CREATE VIEW order_summary_view AS
SELECT ...;
```

View 建立後，既有資料與新資料都會在查詢時由來源資料表即時計算，不需要 backfill。如果 View 定義改變，使用新的
migration 執行 `CREATE OR REPLACE VIEW`，或依資料庫限制先刪除再重建。

## 做法三：Materialized View

Materialized View 會把查詢結果實際儲存起來：

```sql
CREATE MATERIALIZED VIEW order_fulfillment_summary AS
SELECT ...;
```

之後依照需求更新：

```sql
REFRESH MATERIALIZED VIEW order_fulfillment_summary;
```

它與一般 View 的差別是：

```text
普通 View：儲存 SQL，查詢時重新計算
Materialized View：儲存計算完成的結果
```

特性：

- 查詢很快。
- 會增加資料庫儲存空間。
- 資料可能不是即時的。
- 必須決定何時 refresh。
- 適合複雜 JOIN、聚合及統計報表。

它適合可以接受數秒或數分鐘延遲的報表，不適合拿來判斷即時庫存扣減。

### 初始化

需要建立 Materialized View，並產生第一份完整結果。以 PostgreSQL 為例，直接建立時預設會立即填入既有資料：

```sql
CREATE MATERIALIZED VIEW order_fulfillment_summary AS
SELECT ...;
```

若不希望 migration 因大量計算而執行太久，可以先只建立結構，再另外執行第一次 refresh：

```sql
CREATE MATERIALIZED VIEW order_fulfillment_summary AS
SELECT ...
WITH NO DATA;

REFRESH MATERIALIZED VIEW order_fulfillment_summary;
```

第一次 refresh 就是它的 backfill。之後仍須透過排程、管理指令或資料變更觸發 refresh。資料量很大時，第一次 refresh
應與 schema migration 分開執行，避免長時間鎖住部署流程。

## 做法四：同步維護 Projection Table

應用程式執行 command 時，可以在同一個流程內同時更新主要資料與讀取模型：

```text
PlaceOrderUsecase
 ├─ 儲存 Order
 └─ 更新 OrderSummaryProjection
```

例如建立專門的讀取資料表：

```sql
CREATE TABLE order_summary_projection (
    order_id UUID PRIMARY KEY,
    status VARCHAR(30),
    total_amount DECIMAL,
    item_count INTEGER
);
```

特性：

- 查詢快速且簡單。
- 可以與主要資料放在同一個 transaction。
- 一致性比較強。
- Command use case 會多一個更新責任。
- 每個寫入流程都不能漏掉 Projection 更新。

這仍然可以算是 CQRS：寫入模型與讀取模型不同，只是它們沒有分散到不同服務。

### 初始化

需要先用 database migration 建立 Projection table。若系統尚未有業務資料，建空表即可，之後每個 command 在同一個
transaction 中維護它。

如果來源資料表已經有資料，則需要執行一次 backfill，例如：

```sql
INSERT INTO order_summary_projection (order_id, status, total_amount, item_count)
SELECT o.id, o.status, o.total_amount, COUNT(oi.id)
FROM orders o
LEFT JOIN order_items oi ON oi.order_id = o.id
GROUP BY o.id, o.status, o.total_amount
ON CONFLICT (order_id) DO UPDATE SET
    status = EXCLUDED.status,
    total_amount = EXCLUDED.total_amount,
    item_count = EXCLUDED.item_count;
```

Backfill 應設計成可重複執行。上線時要避免 backfill 與新 command 互相覆蓋較新的結果；常見作法是在維護模式執行，或先
部署同步寫入、記錄切換點，再補既有資料並核對結果。

## 做法五：事件驅動的 Projection Table

這是談論 CQRS Projection 時最常想到的做法：

```text
OrderPlaced
    ↓
Outbox / Message Broker
    ↓
OrderFulfillmentProjectionHandler
    ↓
order_fulfillment_projection
```

不同事件可以持續更新同一筆讀取模型：

```text
OrderPlaced                 → 建立履約摘要
OrderAllocationCompleted    → 更新 allocation_status
ShipmentPacked              → 更新 shipment_status
ShipmentHandedOver          → 更新 fulfillment_status
OrderCancelled              → 更新 order_status
```

查詢 API 最後只需要讀取 Projection table：

```sql
SELECT *
FROM order_fulfillment_projection
WHERE order_id = :orderId;
```

它不需要在每個 HTTP request 中即時呼叫 Ordering、Inventory、WMS 和 Temporal。

特性：

- 跨 bounded context 的查詢很快。
- 各來源系統不必在查詢當下同時在線。
- 會增加資料庫空間與事件處理邏輯。
- 通常採用最終一致性。
- 必須處理重複事件、事件順序及重建。

Projection handler 通常必須具備 idempotency。相同的 `OrderAllocationCompleted` 收到兩次時，結果仍然必須等同只處理
一次。可以利用 Inbox、event ID 或版本欄位達成。

這種方式適合資料來自不同 bounded contexts、查詢需要呼叫多個服務，而且可以接受短暫延遲的場景。目前專案的
`OrderFulfillmentView` 最符合這些條件。

### 初始化

需要初始化，而且通常是所有做法中最需要明確設計初始化流程的一種。建立空 table 與啟動 consumer 只能處理之後的新
事件，無法自動補出事件訂閱開始前的歷史狀態。

初始化方式依系統保留的歷史資料而定：

1. 有完整且可重播的事件紀錄時，清空或建立新的 Projection table，從最早的事件開始 replay，依正常 handler 的規則重建
   每一筆 Projection。
2. 沒有完整事件紀錄時，從各來源系統讀取目前狀態，執行一次 snapshot backfill，再消費 backfill 開始之後的增量事件。
3. 資料量大或不能中斷查詢時，先建立 shadow table，在背景完成 backfill 與事件追趕，驗證後再切換查詢使用的 table 或
   View。

一個不漏事件的典型流程如下：

```text
記錄事件切換點或 consumer offset
              ↓
從來源資料建立 snapshot/backfill
              ↓
從切換點開始消費累積的事件
              ↓
追上最新位置並核對筆數與關鍵欄位
              ↓
開放 Query API 或切換到新 Projection
```

切換點可以是 broker offset、事件序號、版本或時間水位，但必須能精確判斷 backfill 已涵蓋哪些變更。Replay 與正常消費都
應共用具備 idempotency 的 handler，並保存 consumer checkpoint。若事件只有短期保留，不能把完整 replay 當成唯一的
重建策略，還需要來源 snapshot 或定期快照。

## 做法六：記憶體 Projection

Projection 也可以保存在 JVM 記憶體：

```java
Map<OrderId, OrderFulfillmentView> projections;
```

事件進來時更新 Map，查詢時直接讀 Map。

優點：

- 讀取速度非常快。
- 適合短期且可丟失的即時統計。

問題：

- JVM 重啟後資料消失。
- 多個 application instance 之間不會自然同步。
- 資料量增加會占用 heap。
- 必須處理資料重建。
- 不適合作為重要業務資料的唯一來源。

一般業務系統不會把主要 Projection 只放在 JVM 記憶體。如果需要 cache，通常會是：

```text
Database projection table
          ↓
Redis 或 local cache
```

資料庫仍然是 Projection 的可靠儲存來源。

### 初始化

需要在每個 application instance 啟動時初始化，因為 JVM 重啟後 Map 是空的。可依可靠性需求選擇：

- 暫時性統計：從空 Map 開始，只累積啟動後的新事件，不保證包含歷史資料。
- 有資料庫 Projection：啟動時先載入 database snapshot，再接收後續事件。
- 有完整事件紀錄：從 checkpoint 或最早事件 replay；若有 snapshot，先載入 snapshot，再 replay snapshot 之後的事件。

初始化期間若 Query API 已可用，必須明確表示資料尚未 ready，或等到追上最新事件後再通過 readiness check，避免把不完整的
Map 當成正確結果。多 instance 部署時，每個 instance 都要各自初始化。

## 做法七：搜尋與分析型 Projection

Projection 不一定存在關聯式資料庫，也可以投影到其他針對查詢最佳化的系統：

- Elasticsearch：全文搜尋。
- Redis：高速查詢。
- ClickHouse：分析報表。
- Data warehouse：商業分析。

例如：

```text
ShipmentCreated / ShipmentPacked / ShipmentDispatched
                         ↓
                Elasticsearch shipment index
```

這些系統是針對特定需求建立的次要讀取模型，不能自然取代主要交易資料庫。

### 初始化

需要先建立目標端的結構，例如 Elasticsearch index/mapping、Redis key schema、ClickHouse table 或 warehouse schema。若來源
已經有歷史資料，還需要 bulk backfill：

```text
建立新 index/table
        ↓
記錄增量同步的切換點
        ↓
批次匯入既有資料
        ↓
套用切換點之後的事件或 CDC 變更
        ↓
驗證後切換 index alias 或查詢設定
```

Backfill 期間仍要保留新變更，否則完成批次匯入時資料已經落後。Elasticsearch 常用新 index 加 alias 原子切換；分析平台則
常用 staging table 或分區交換。Redis 若只是 cache，也可以不做完整 backfill，讓資料在 cache miss 時逐步建立。

## 各種做法的差異

| 做法 | 額外儲存資料 | 即時性 | 複雜度 | 是否需要初始化 | 常見用途 |
| --- | ---: | ---: | ---: | --- | --- |
| 查詢時轉換 | 無 | 高 | 低 | 否；查詢時即時計算 | 一般列表與明細 |
| Database View | 通常無 | 高 | 低 | 只需建立 View，不需補資料 | 封裝複雜 SQL |
| Materialized View | 有 | 中 | 中 | 是；建立時填入或首次 refresh | 統計報表 |
| 同步 Projection Table | 有 | 高 | 中 | 是；建表，既有系統另做 backfill | 同資料庫快速查詢 |
| 事件驅動 Projection | 有 | 最終一致 | 高 | 是；事件 replay 或 snapshot 加增量追趕 | 跨 context 查詢 |
| JVM 記憶體 Projection | 占用 heap | 高 | 中至高 | 是；每次啟動載入 snapshot 或 replay | 暫時性即時資料 |
| 搜尋／分析 Projection | 有 | 最終一致 | 高 | 是；建結構、bulk backfill、追趕增量 | 搜尋與分析 |

## CQRS 不等於一定要建立 Projection Table

CQRS 的核心只是：

```text
Command model：適合修改業務狀態
Query model：適合回答查詢
```

最輕量的形式可以只是：

```text
Command → Aggregate Repository → Order
Query   → Read Repository      → OrderView
```

Command 使用 aggregate，Query 直接使用 SQL 查成 View，就已經分開讀寫模型。它不要求 Event Sourcing、Message Broker、
第二個資料庫、JVM memory cache，也不要求每一個 View 都有自己的資料表。

## 套用到目前專案

可以分成三個層級。

### 第一層：直接 SQL Projection

適合：

- `OrderSummaryView`
- `StockOperationView`
- 一般 shipment 明細
- 最近訂單列表

形式如下：

```text
QueryService → ReadRepository → SQL → View
```

這些查詢目前不需要新增 Projection table。

### 第二層：事件驅動 Projection

最適合的候選是 `OrderFulfillmentView`，因為它需要整合 Ordering、Inventory、WMS 與 Workflow：

```text
各 bounded context 的 IntegrationEvent
                    ↓
order_fulfillment_projection
                    ↓
OrderFulfillmentQueryService
```

這會增加一張資料表，但可以移除查詢當下對多個 context 和 Temporal 的即時依賴。

### 第三層：有實際需求時才建立

可能的候選包括：

- Picking work queue
- Short-pick exception dashboard
- Shipment dispatch dashboard
- Wave progress
- Inbound quarantine dashboard

等到真的出現 UI、報表或大量查詢需求，再建立相應的 Projection，不必預先建造。

## 如何選擇

遇到一個 Query 時，可以依序確認：

1. 直接查既有資料表是否已經足夠快？
2. 能否用專門 SQL 避免載入完整 aggregate？
3. 是否需要跨多個 bounded contexts 組合資料？
4. 是否可以接受短暫的最終一致？
5. 維護重複資料的成本，是否小於每次重新組合的成本？

如果普通 SQL 已經足夠，就使用查詢當下的 Projection。只有當跨 context、複雜 JOIN、聚合或查詢量造成實際問題時，
才建立持久化 Projection table。

> Projection 是為某個查詢準備資料形狀；是否建立新資料表、是否使用事件、是否放進記憶體，都是不同的實作選擇，
> 而不是 Projection 本身的必要條件。
