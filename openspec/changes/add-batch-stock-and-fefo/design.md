## Context

`stock_pools` 目前是 `(sku)` 唯一、四個數字欄位。它承載了兩件已經不成立的假設：**庫存不分
貨主**（R1 讓訂單分了，庫存沒跟上）與**庫存不分倉**（R2 建了倉庫，庫存沒跟上）。

`AllocationService.allocate()` 現在的形狀是「一張訂單對一個 `StockPool`」——
`requireDemandIsEntirelyInThisPool()` 明文要求訂單的需求集合恰好等於這一個池的 SKU。整條
配貨路徑、`AllocationPolicy` 的兩個實作、`OrderAllocationCoordinator` 的三處持久化，都建立在
「一個 SKU 一列」上。

**約束**：`V2` 與 `V4` 皆未部署至任何環境（沿用前三個 change 的判準，可改寫）；六件動工前
事項已於 2026-07-29 全數定案，見 roadmap 的 R3 段落。

## Goals / Non-Goals

**Goals**

- 讓跨貨主隔離由資料庫保證，而不是靠「剛好沒人踩到」
- 讓配貨成為一個有內容的演算法：篩選、排序、跨列取用
- 讓「有貨但不可售」在畫面上看得見，且理由具體（已過期）
- 讓 partition key 與新的爭用群組一致，不留下 single-writer 失效的中間狀態

**Non-Goals**

- **不做批號、不做到貨表、不做品質狀態**。三者的取捨過程記在 roadmap 的動工前第 1、5 件，
  本 change 不重新討論。
- **不做部分配貨**。ship-complete 不變：整張單配到，或整張單缺貨。
- **不放寬多行**。收單仍限定恰好一行，多行是 R8。
- **不改 `stock_pools` 的表名**。理由見 `dom-order-intake-scope.md`。
- **不動編排權**。`AllocationService` 仍直接改 `Order`，那是 R4 的工作，且 roadmap 禁忌第 3
  條要求 R3 → R4 串行。

## Decisions

### 改寫 `V2` 與 `V4`，不新增 migration

沿用 `add-demo-console-api`、`fix-outbox-partition-key-semantics`、
`add-owner-and-order-line-model`、`add-warehouse-and-owner-assignment` 的判準：**schema 尚未
部署至任何環境即可改寫**。這是第五次。

新開一支的代價是 migration 歷史會記錄一段「`stock_pools` 建成一個 SKU 一列、然後改成一批
一列」的假歷史——而那個中間形狀從未在任何環境存在過。

代價已知：既有 Postgres volume 必須 `./e2e/perf/run.sh down` 後重建，**不得以 `flyway repair`
略過** checksum 不符。

### `StockPool` 從「一個 SKU 的池」變成「一批貨」

類別名不改（表名不改的同一個理由），但它承載的東西變了：新增 `ownerId`、`nodeId`、`inDate`、
`expiryDate` 四個不可變欄位，以及 `isSellable(LocalDate today)`。

**`consume()` 與 `reserve()` 分開**：`reserve()` 是配貨時鎖住額度，`consume()` 是出貨時真正扣掉
在手量。R3 只用 `reserve()`，`consume()` 為 R7 的兩本帳準備——但 `ReservationStatus.CONSUMED`
必須在本 change 加入，因為 R4 的 `demand_lines` view 會用它做「已滿足」的謂詞，而 R4 緊接在後。

### 配貨從「一個池」變成「一組批」

`AllocationService.allocate()` 的簽章由 `(Order, StockPool, Instant)` 改為
`(Order, List<StockPool>, Instant)`，而那個 list 是**已依 FEFO 排序的可售批**。

排序與篩選**留在 repository**（`findSellableBatchesInFefoOrder`），不進 domain service：

| 作法 | 判定 |
| --- | --- |
| repository 回傳全部批、service 排序 | 否。批數隨時間成長，把不可售的也載進記憶體只為了丟掉 |
| **repository 依 `(expiry_date, in_date, id)` 排序並篩掉過期** | **採用**。排序與篩選在資料庫做，且該順序正是 index 的順序 |

`requireDemandIsEntirelyInThisPool()` 改為 `requireBatchesMatchDemand()`——所有批必須屬於同一個
`(owner, node, sku)`，且該三元組等於訂單那一行的需求。這條檢查仍然存在，因為它守的是「呼叫端
給錯了批」這種程式錯誤。

### 預留的粒度是「訂單行 × 批次」

一條行吃到三個批就是三筆 `stock_reservations`。外鍵由 `order_id` 改為 `order_line_id`。

替代方案是「一條行一筆預留，內含批次清單」——否決，因為釋放與消耗都是逐批發生的（出貨時
某一批先被揀完），一筆多批的預留無法表達部分消耗，而那正是 R7 兩本帳要對的東西。

### FEFO 的排序鍵是三層

`(expiry_date, in_date, id)`：

1. **效期**——FEFO 的本體
2. **入庫日**——同效期時先進先出。同效期不同日到貨很常見（同一生產批分兩車送到）
3. **id**——最後的定序保證

第二與第三層不是裝飾：少了它們，同效期的批之間順序不定，**配貨結果不可重現**（同樣的庫存跑
兩次配到不同批），而且任務 9 的防死鎖排序鍵會失效。

### partition key 與唯一鍵在同一個 change

`ownerId/nodeId/skuCode`，改在 `OrderingDomainEventTranslator` 與 `ReplenishmentProbeController`。

**必須同批做**，理由不是「分開會壞」而是**兩個方向的風險不對稱**：庫存先分、key 後改的中間
狀態只是過度序列化（慢但正確）；key 先改、庫存後分則是並行更新同一列（衝突暴增到重試耗盡
後落 DLT）。同一個 change 就不必記這條順序規則。

`AllocationDomainEventTranslator` 不動——它發往下游的結果事件，下游更新的是 `Order` 那一列，
爭用群組本來就是 `orderId`。

### 補貨喚醒要有批次上限

現在 `findBackordersBySkuInFifoOrder()` 無上限，而 `AllocationFifoReplenishmentBatchIntegrationTest`
已是「單次補貨喚醒 500 張」的情境。分批之後這從效能問題升級為**正確性問題**：一次補貨涉及的
批數由佇列內容而非事件決定，鎖範圍不可預測，而防死鎖排序依賴「事先知道會碰哪些列」。

作法：**上限以張數為維度、可設定**；超出時發一則續做事件（同 topic 同 partition key）；
**終止條件是「本輪喚醒張數 < 上限即不續做」**——喚醒數不足代表佇列已清空或被 head-of-line
blocker 卡住，再送一次結果相同，這同時保證進展性。

前提是 FIFO 只保證「補貨當下的佇列快照」（見 `dom-promising-scope.md` 的「補貨的三個決定」）。
若那條契約改成嚴格全域 FIFO，本項只能退回同交易內分頁，而那沒有縮短交易。

## Implementation Contract

**資料表**

`stock_pools`：唯一鍵 `(owner_id, node_id, sku_code, in_date, expiry_date)`；`expiry_date`
**NOT NULL**（PostgreSQL 的 unique 把 NULL 視為互不相同，可空會讓同日到貨的無效期商品各成
一列而非合併）；外鍵 `(owner_id, sku_code)` → `skus`。

`stock_reservations`：外鍵改為 `order_line_id`；`status` 加 `CONSUMED`。

**可觀察行為**

- 兩個貨主的同碼 SKU 各有自己的庫存列；甲貨主下單不影響乙貨主的可用量
- 訂單指定北倉時，只有北倉的批參與配貨
- 已過期的批不參與配貨，且該狀態在庫存頁上以具體理由呈現（「已過期」而非留白）
- 一條行的需求跨多批時，依效期由近到遠取用，並產生多筆預留
- 同效期的兩批依入庫日決定先後；同樣的庫存與訂單重跑，配到的批相同
- 補貨帶倉別、入庫日、效期；同五維鍵的補貨加到既有列，其餘新開列
- `sku` 分區策略下，同一個 `(貨主, 倉, SKU)` 的事件落在同一個 partition；不同貨主的同碼 SKU
  落在不同 partition

**種子**

同一個 SKU 三批：近／中／遠效期，**其中兩批同效期不同入庫日**（否則 tie-breaker 沒有測到），
另有一批已過期。一張需求跨兩批的訂單。

**不在範圍**：批號、到貨記錄、品質狀態、部分配貨、多行訂單、編排權歸位。

## Risks / Trade-offs

**[三支 SIT 的前提失效]** → `AllocationHotSkuConcurrencyIntegrationTest`、
`AllocationFifoReplenishmentBatchIntegrationTest`、`AllocationConcurrencyEndToEndIntegrationTest`
都建立在「一個 SKU 一列」上。**須重新設計而非微調**——熱點的定義從「一個 SKU」變成「一個
批次」。最容易犯的錯是讓它們「看起來還會過」但已經測不到原本要測的東西：把 1,000 張單打在
一個 SKU 的三個批上，衝突就分散了，樂觀鎖競爭的強度完全不同。**緩解**：熱點測試要把庫存
集中在單一批次上，才維持原本的競爭強度。

**[`AllocationFifoReplenishmentBatchIntegrationTest` 另受批次上限影響]** → 500 張的單次喚醒
會變成多輪續做，斷言要從「一次補貨事件後的最終狀態」改為「續做收斂後的最終狀態」。
**head-of-line blocking 的斷言必須保留**——那是這支測試存在的理由。

**[v3 的吞吐對比失去基準]** → partition key 改成三維之後，`e2e/perf/README.md` 記的
270.8 vs 203.3 orders/s 是在裸 sku 策略下量的。**那組數字要標註為「改動前」**，要不要重測是
獨立的決定（重測要跑完整的暖機方法論，見該檔）。

**[既有 volume 必須重建]** → 本專案第五次，症狀明確（Flyway checksum 不符、啟動失敗），
不會靜默。風險在於忘記，而不是做錯。

**[`ReservationStatus` 加值會影響 R4 的 view]** → `CONSUMED` 會被 R4 的 `demand_lines` view
用在「已滿足」謂詞裡（`status IN ('ACTIVE','CONSUMED')`）。**日後再擴充該 enum 時必須同步檢查
view 定義**——漏掉會讓已出貨的訂單重新出現在待配佇列，而當下沒有任何測試會發現。

## Migration Plan

1. `./e2e/perf/run.sh down`，移除既有 Postgres volume。
2. 套用改寫後的 `V2` 與 `V4` 與新的 seed。
3. `./e2e/perf/run.sh perf`，確認 k6 既有 thresholds 全數通過。

Rollback 就是 git revert 加一次 `down`／`up`。

## Open Questions

- **熱點測試該把庫存集中在單一批次，還是改測「批次層級的熱點」？** 前者維持原本的競爭強度、
  改動最小；後者更貼近分批後的真實情況，但要重新定義什麼叫「熱」。實作時決定，並把選擇的
  理由寫進該測試的註解——那支測試的價值全在它測到真實衝突。

- **批次上限的預設值。** 太大則交易長、鎖範圍不可預測；太小則續做事件頻繁。沒有先驗的正確
  答案，實作時以壓測觀察決定，並記錄當時的觀察值。
