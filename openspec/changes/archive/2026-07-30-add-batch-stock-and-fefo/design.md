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
- 讓「有貨但配不到」在畫面上看得見，且分得出是過期還是被預留光
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
`expiryDate` 四個不可變欄位，以及 `isExpired(LocalDate today)`。

**`consume()` 與 `reserve()` 分開**：`reserve()` 是配貨時鎖住額度，`consume()` 是出貨時真正扣掉
在手量。R3 只用 `reserve()`，`consume()` 為 R7 的兩本帳準備——但 `ReservationStatus.CONSUMED`
必須在本 change 加入，因為 R4 的 `demand_lines` view 會用它做「已滿足」的謂詞，而 R4 緊接在後。

### 配貨從「一個池」變成「一組批」

`AllocationService.allocate()` 的簽章由 `(Order, StockPool, Instant)` 改為
`(Order, List<StockPool>, Instant)`，而那個 list 是**已依 FEFO 排序的可配批**。

排序與篩選**留在 repository**（`findAllocatableBatchesInFefoOrder`），不進 domain service：

| 作法 | 判定 |
| --- | --- |
| repository 回傳全部批、service 排序 | 否。批數隨時間成長，把配不到的也載進記憶體只為了丟掉 |
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

## 補充決定（review 期間）

### 對外的配貨結果事件是通知，不是狀態傳輸

`OrderAllocatedIntegrationEvent` 原定攜帶批次清單（每批帶 `orderLineId`、`stockPoolId`、效期、
數量），review 時否決，改為 `(eventId, orderId, ownerId, allocatedAt)`。

| 作法 | 判定 |
| --- | --- |
| 事件攜帶批次清單 | 否。清單描述的是**會變的狀態**——取消訂單會釋放那些預留，而 `ordering.order-events` 與 `promising.allocation-events` 是兩個 topic、沒有順序保證 |
| **事件只通知，消費端回頭讀 `stock_reservations`** | **採用**。那份紀錄是持久的、含 RELEASED 的歷史，且 R4 的 `demand_lines` view 已在其上 |

第三個理由與正確性無關但同樣要緊：**消費端還不存在**。履約層尚未設計，預先塞欄位是在猜它需要
什麼，而契約的欄位加容易、砍難——猜錯的方向剛好是最貴的那個。R7 屆時加上它真正需要的即可。

`ownerId` 例外保留：它是每張單唯一、結構上不會摺錯的維度，而多租戶的下游要能不查就判斷「這則
跟我有關嗎」。`OrderPlacedIntegrationEvent` 已以同一理由帶了它。`nodeId` 不帶——「去哪個倉揀」
是履約層的需求，屬 R7。

**附帶效果（不是動機，但值得記）**：`OrderAllocationCoordinator` 裡以 `.get(i)` 對齊 `picks` 與
`reservations` 兩條平行清單的那段程式，唯一目的就是組這份批次清單。清單移除之後那個索引對齊
需求消失，連同 `allocateBackorders` 裡一個以 record（內含可變的 `Order` 與 `StockPool`）當
`HashMap` key 的脆弱處。曾考慮引入 `ReservedBatch(pick, reservation)` 包裝型別來消除索引對齊，
在這個決定之後不再需要。

### 用詞：`expired` 與 `allocatable`，不用 `sellable`

`sellable` 全面撤除。兩個理由：

1. **主詞錯了。** 3PL 不賣貨，貨主才賣。倉庫要回答的是這批貨**出不出得了**。
2. **它同時承載兩件事。** 原本 `isSellable` 只看效期，但「配不配得到」還要看有沒有量。

改為兩個各有所指的詞：

| 詞 | 意思 | 用在 |
| --- | --- | --- |
| `expired` | 效期過了沒有——批的固有事實 | `StockPool.isExpired(today)`、REST 回應 |
| `allocatable` | 沒過期**且**還有量——配貨拿得到的 | `findAllocatableBatchesInFefoOrder`、`NO_ALLOCATABLE_STOCK` |

因此查詢多了一個條件 `on_hand_quantity > reserved_quantity`：全被預留光的批對配貨而言與不存在
沒有差別，留著只會讓每個呼叫端各自記得跳過它。行為上唯一的變化是「所有批都被預留光」從
`INSUFFICIENT_ATP` 變成 `NO_ALLOCATABLE_STOCK`，配到的批與數量不變。

REST 的 `unsellableReason` 一併移除——它永遠只會是 `null` 或 `"EXPIRED"`，是為已明確不做的
待驗、封鎖、破損預留的欄位。`expired` 與 `availableToPromise` 兩個欄位各講一件事，讀的人合起來
就分得出「過期了」與「被預留光了」。

### 「今天」必須挑一個營運時區

效期以日期比對，因此要把 `Instant` 切成某一天。`Clock` 是 UTC 的，而 `LocalDate.now(utcClock)`
在台北時間每天 00:00–08:00 之間還停在昨天——那八小時會把已經過期一天的貨判成未過期，貨就出去
了，不會有任何錯誤浮現。

收成 `common/time/BusinessCalendar`，時區由 `archone.business-zone` 設定（預設 `Asia/Taipei`）。
規則只有一份，就不會有人漏掉 `withZone`。正確的模型是時區屬於倉庫，記在
`docs/system-layer-map.md` 的「倉別時區：已識別但未排程」。

### 對外事件一律只帶識別，例外是來自系統外部的事件

`OrderAllocatedIntegrationEvent` 的瘦身（見上）套用到全部對外事件之後，規則長這樣：

| 事件 | 帶什麼 | 為什麼 |
| --- | --- | --- |
| `OrderPlacedIntegrationEvent` | orderId, placedAt | 消費端 `AllocateOrderCommand(orderId)` 反正從 DB 重讀整張單 |
| `OrderCancelledIntegrationEvent` | orderId, cancelledAt | 同上 |
| `OrderAllocatedIntegrationEvent` | orderId, allocatedAt | 同上 |
| `BackorderCreatedIntegrationEvent` | orderId, backorderedSince | 同上 |
| `StockReplenishedIntegrationEvent` | **五個維度 + 數量** | **例外**：來自上游倉儲系統，沒有本地聚合根可重讀，事實只存在於訊息裡 |
| `BackorderWakeRequestedIntegrationEvent` | ownerId, nodeId, sku | **例外**：同上，它不對應任何一張訂單 |

**規則**：關於我們自己聚合根的事件只帶識別；來自外部的事件必須帶事實。

**`ownerId` 與 `nodeId` 也不帶。** 曾經以「多租戶／多倉的下游要能不查就過濾」為理由把 `ownerId`
放進來，review 時推翻：

| | `batches`（砍） | `sku` / `quantity`（砍） | `ownerId` / `nodeId`（砍） |
| --- | --- | --- | --- |
| 有現在的害處嗎 | **有**，描述會被取消釋放的狀態 | **有**，payload 逼著 eager 求值，破壞 `Supplier` 的逃生路徑 | 沒有 |
| 有現在的好處嗎 | 沒有 | 沒有 | **沒有**——這個 repo 裡沒有任何按貨主或按倉過濾的消費端 |

前兩者砍掉與有沒有消費端無關，它們本身就是錯的。後者兩邊都空，那就**不該由規則決定，而該由真實的
消費端決定**——拿「租戶是安全邊界」這種抽象差異去合理化其中一個留、另一個不留，就是為想像中的下游
設計，只是包裝得比較好聽。

決定的依據是方向的不對稱：**加欄位對消費端是非破壞性的（舊消費端忽略不認識的欄位），砍欄位是破壞
性的。** 所以起點取最小，等真的出現按貨主或按倉過濾的消費端時再加——到那時會知道要加哪一個、為什麼。

代價：屆時那個消費端得為每一則無關訊息付一次查詢，直到欄位補上。

**一併記下推導過程的教訓**：那三條檢查（不變、結構單值、動工前需要）**只能說明什麼不准進 payload，
不能說明什麼必須進**。必須進的只有識別、去重與時間；其餘一律要有讀它的人。

### partition key 改粗成 `(貨主, 倉)`，不含 SKU

review 時先問「partition key 是否該只用訂單層欄位」，我一度答「不行，爭用的資源由五個維度識別，
其中 `sku_code` 在行上」。那個回答只在**單行訂單**下成立，而它讓 `sku` 策略帶著一個到期日：

| | `(貨主, 倉, SKU)` | **`(貨主, 倉)`** |
| --- | --- | --- |
| 單行訂單 | ✓ 精準 | ✓ 正確，但同貨主同倉的不同 SKU 也排隊 |
| 多行多 SKU（R8） | **✗ 摺不出單一個 key**——ship-complete 要求整籃 ATP 同一交易判斷，per-SKU 的 writer 管轄必然被跨越 | **✓ 一個 writer 看得到整張單** |
| 事件發出時取得 | 需從行摺疊（`requireSingleSku`，會拋錯） | 貨主與倉都在 header 上 |

**拿掉 SKU 不是繞過問題，是消掉它。** R8 原本有一條「本策略必須退場」的待辦，現在沒有了。

代價是**過度序列化**——同貨主同倉、不同 SKU 的訂單本來永遠不會撞，現在也排隊。方向是刻意選的：
太粗只是慢但正確，太細則直接失去 single-writer。而規模判準支持它：壓測量到「完全沒有
single-writer」（v1）也只掉約 25% 吞吐（203.3 vs 270.8 orders/s），而台灣量體下容量餘裕有一個
數量級，那 25% 收不到。**粗 key 那一格尚未量測**，任務 10.3 之後可另跑一輪 v4 補上。

設定值由 `sku` 改名為 `stock`：名字說**序列化什麼**而不是**用哪幾個欄位**，日後調整組成不會讓
名字說謊。

**連帶的簡化**：`LineSnapshot.requireSingleSku()` 沒有使用者了（刪除）；`partitionKey` 不再需要
`Supplier` 延後求值；translator 那段「策略有到期日、唯一出路是退場」的長篇 Javadoc 改寫成「曾經
有，拿掉 SKU 之後沒有了」。

領域事件的 `lines` 則分兩種處置：

| 事件 | `lines` | 理由 |
| --- | --- | --- |
| `OrderPlaced` | **保留** | 「收了一張單」而不說收了什麼，是個空洞的事實。收單是這個事件唯一要描述的事 |
| `OrderBackordered` | **保留** | 同上，缺貨的內容就是那些行 |
| `OrderCancelled` | **移除** | 取消是整單行為，事件要說的是「哪張單、什麼時候」；行的內容不構成這個事實的一部分 |

三者原本都帶 `lines`，因為 translator 要從行摺出 partition key 的 SKU。key 改粗之後那個需求消失，
於是它們各自回到「這個事實本身需要什麼」——而答案不一樣。

in-process 事件加減欄位兩個方向都便宜，所以判準是**描述得準不準**，而不是對外事件那條「沒有讀取
者就不要帶」（那條的依據是契約的不對稱性：加容易砍難）。

### 一個被文件掩蓋的 bug

translator 用 `Supplier<String>` 延後求值 SKU，Javadoc 說那是為了讓「退回 order-id 策略」這條出路
真的走得通——order-id 不需要 SKU，先算出來會讓多 SKU 的訂單在 `requireSingleSku` 拋錯。

**但 payload 原本帶 `sku`，逼著在呼叫端 eager 求值，那個延後一直沒有作用。** 對外事件瘦成只帶
識別之後才真的成立。新增一支測試（`orderIdStrategyDoesNotNeedTheSkuForPlacedEither`）釘住它，並
以「把 eager 求值加回去、確認該測試變紅」驗證過。

**這兩件事必須一起維持**：任何一天有人把 SKU 加回 payload，這條出路就又斷了。

### 對外事件一律經 translator，usecase 不碰 outbox

`ReplenishmentUsecase` 原本直接注入 `OutboxAppender`，自己組 `BackorderWakeRequestedIntegrationEvent`
並 append。review 時改掉：它發 `BackorderWakeContinuationRequired` 領域事件，由
`AllocationDomainEventTranslator` 譯成對外事件。

理由不是對稱性本身，而是那個例外的具體代價：它讓一支 usecase 成為**唯一知道 outbox 存在**的
usecase，也是唯一在 translator 之外構造對外事件的地方。translator 這一層存在的用途正是讓
「領域事實」與「怎麼送出去」（topic、partition key、aggregate 欄位）只有一處交會；開一個例外之
後，下一個要發對外事件的人有兩個看起來都對的範本可抄，而其中一個會繞過那唯一的交會處。

改完之後 `OutboxAppender` 的使用者只剩 `OrderingDomainEventTranslator` 與
`AllocationDomainEventTranslator` 兩個。

測試也跟著搬家：usecase 的測試斷言「發了那則領域事件」，topic／key／aggregate 的斷言搬到
`DomainEventTranslatorTest`。後者順便把一條先前沒測到的性質釘住——**續做事件一律以爭用群組當
key，不套用 `partition-key-strategy`**，與配置結果事件（一律 orderId）相反。
