# 履約層（最小版）：兩本帳與短揀對帳

狀態：分析，未確認

日期：2026-07-26

## 這份文件回答什麼

本文件定義履約層的**最小可行範圍**：只保留「實體帳與邏輯帳會不一致」這一個 DOM
沒有的問題類型，其餘倉庫作業一律排除。

| 相關文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [system-layer-map.md](system-layer-map.md) | 全流程分層、跨層契約、帳務交會點、用詞決定 |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | 訂單層 ① 收單編排、訂單與商品資料模型、貨主 |
| [dom-promising-scope.md](dom-promising-scope.md) | 訂單層 ② Promising：批次庫存模型與 FEFO 配貨 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | 訂單層 ③ Sourcing/Routing |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | 深做版：**從本文件加什麼**，只寫增量 |

**現況：此層完全不存在。** 本文件描述的一切都是從零建立。

## 為何切最小版

履約層與 DOM 有大量同構的部分——聚合加狀態機、樂觀鎖併發、outbox／Kafka、四層分層
——再做一遍不增加新的能力類別。

只有一件事是 DOM 完全沒有的：

> DOM 裡所有操作都可補償。reservation 可以 release，訂單可以取消，全部是資料操作。
> 履約層有一類問題不能用資料回滾解決：**系統帳與實際不符**。

最小版保留的就是這一件事，其餘全部排除。

## 前提

**本系統是 3PL**：倉庫不擁有貨。同一儲位上可能同時放著不同貨主的同一 SKU，兩者不可
互相調用。因此 `LocationStock` 與所有揀貨指令都必須帶 `ownerId`。

---

## 範圍

沿用完整 WMS 的職責編號（W1～W18），本文件的判定：

| # | 職責 | 最小版 | 說明 |
| --- | --- | --- | --- |
| W1 | 儲位模型 | **扁平** | 只有 `code`，不解析階層。但 **code 必須用階層格式**，見「可升級性」 |
| W2 | 儲位庫存（實體帳） | **✓** | 最小版的核心 |
| W3 | 收貨上架 | ✗ | 儲位庫存由 seed 直接建立 |
| W4 | 出貨單 | **扁平** | 兩態的 `Shipment`，見「可升級性」 |
| W5 | 揀貨任務產生 | **✓** | — |
| W6 | 揀貨執行 | **✓** | — |
| **W7** | **短揀處理** | **✓** | **最小版存在的唯一理由** |
| W8 | 複核裝箱 | ✗ | — |
| W9 | 出庫離倉 | **併入 W6** | 揀貨確認即出貨，無中間狀態 |
| W10 | 回架 putback | ✗ | 「已揀未出」的窗口在最小版不存在 |
| W11 | 盤點任務 | ✗ | 短揀只修正帳，不產生後續任務 |
| W12～W18 | 波次、庫內移動、補貨策略、批號、WES／WCS、YMS、績效 | ✗ | 見「明確不做」 |

### W9 併入 W6 的後果

揀貨確認即出貨，因此**「已揀未出」這個中間狀態不存在**。連帶：

| 影響 | 說明 |
| --- | --- |
| W10 回架不需要 | 沒有貨停在出貨區的時機 |
| 取消的補償路徑由四種簡化為兩種 | 見「跨層交會點」 |

這是最小版真正的取捨。回架是「物理動作的補償是另一個物理動作，不是資料回滾」的最好
例子，比短揀更能說明履約層與 DOM 的本質差異——但它需要完整的 `Shipment` 生命週期才
撐得起來。

---

## 領域模型

### 聚合

| 聚合 | 職責 | 不變條件 |
| --- | --- | --- |
| `Shipment` | **一次交付**，粒度為 `(order, node)`，兩態 | `DEPARTED` 後不可變更 |
| `PickTask` | 為滿足某條 line，從一個儲位揀一個批次 | 實揀數不得超過應揀數 |
| `LocationStock` | 一個儲位上某批次的實體量 | 數量不得為負 |

`Location` 是主檔而非聚合——最小版不解析階層，它只提供 `code` 供顯示與排序。

### 欄位

```text
Location                              主檔
  id, nodeId, code

LocationStock
  (locationId, ownerId, skuCode, inDate, expiryDate) 複合主鍵, quantity, version

Shipment
  id, orderId, ownerId, nodeId, status, createdAt, departedAt

PickTask
  id, shipmentId, orderLineId, locationId, ownerId, skuCode, inDate, expiryDate
  requestedQty, pickedQty, status, confirmedAt
```

`ownerId` 出現在 `LocationStock`、`Shipment`、`PickTask` 三處，因為三者都必須能回答
「這是誰的貨」。少了它，同一儲位上兩個貨主的同一 SKU 就無法區分，揀貨會揀到別人的
貨——這在 3PL 是資料事故等級的缺陷。

`Location` **不帶 `ownerId`**：儲位是倉庫的物理設施，不屬於任何貨主。

### `Shipment` 的粒度是 `(order, node)`

不是「一張訂單一個」，也不是「一條 line 一個」，而是**同一張訂單中被配到同一節點的
所有 line 合成一張出貨單**——`Shipment` 的定義是「一次交付」：一個出貨節點、一個
收件地址。

| 若粒度是 | 問題 |
| --- | --- |
| 一張 order 一個 | 跨節點拆單時表達不出來 |
| 一條 line 一個 | 同節點的多條 line 會變成多個包裹、多次運費 |
| **`(order, node)`** | ✓ 同節點併單、跨節點拆單都能表達 |

最小版是單節點且每張單只有一條 line，三者退化成一對一，看不出差別。但欄位
（`orderId` ＋ `nodeId`）從一開始就是最終形態。

### `PickTask` 為何帶 `orderLineId`

沒有它，揀到 8 個時**不知道這 8 個是為了滿足哪條 line**——訂單層要修正 `StockPool` 與
重新決策，都必須知道是哪條 line 短少。

拆單時一條 line 可能橫跨兩個 `Shipment`，因此這個關聯是**多對一**：

```text
Order 1 · Line A（需求 20）
   ├── Shipment(北倉) ── PickTask → orderLineId = A, qty 12
   └── Shipment(中倉) ── PickTask → orderLineId = A, qty  8
```

指向 `orderLineId` 而非等深做版的 `shipmentLineId`，是為了升級時零搬遷：深做版加入
`ShipmentLine(shipmentId, orderLineId, …)` 作為彙總明細後，`PickTask` 不需要改動。
判準與「可升級性」一節的三處預防相同。

`LocationStock.version` 需要樂觀鎖——兩個揀貨員同時揀同一儲位是真實的併發場景，與
既有 `StockPool` 的熱點 SKU 是同一類問題。

### 狀態機

```text
Shipment    CREATED ──▶ DEPARTED
                └─────▶ CANCELLED

PickTask    PENDING ──▶ PICKED           實揀 = 應揀
                    └─▶ SHORT_PICKED     實揀 < 應揀，觸發 W7
                    └─▶ CANCELLED        出貨單取消且此任務未開始
```

`SHORT_PICKED` 是獨立終態而非 `PICKED` 的變體，因為它觸發完全不同的後續流程。

`DEPARTED` 之後無任何轉移。逆物流不在範圍內，此後的路徑沒有補償手段。

---

## 可升級性

最小版有三處刻意採用**最終形態**而非最簡形態。三者的共同判準：**事後改動會影響別人
的，現在就做對；事後只新增自己的，可以延後。**

| 項目 | 最小版 | 若採最簡形態，升級時要付什麼 |
| --- | --- | --- |
| 儲位 `code` | 階層格式 `A-01-03-02-04`，但不解析 | 任意字串（如 `LOC-001`）→ 升級要**重新編碼所有儲位並重建 seed** |
| 出貨事件 | `ShipmentDeparted` | `OrderPicked` → 升級是**跨 module 的契約變更**，DOM 端 handler、inbox、e2e 全改 |
| `PickTask` 的 FK | `shipmentId` | `orderId` → 升級要**搬 FK、回填 shipments、改所有查詢與測試** |

第三項的代價是多一張很扁的 `shipments` 表與一個兩態的聚合。用這個換掉一次跨聚合重構
與一次跨 module 契約變更。

升級為深做版時，所有改動都是純新增，詳見
[fulfillment-full-scope.md](fulfillment-full-scope.md)。

---

## 主要流程

| # | 觸發 | 動作 | 產出 |
| --- | --- | --- | --- |
| 1 | `OrderAllocated`（含 `ownerId`、`nodeId`、**批次清單**） | 建立 `Shipment`，狀態 `CREATED` | — |
| 2 | 同上，同交易 | 依批次清單在 `LocationStock` 中定位儲位，產生 `PickTask` | `PickTask` 清單 |
| 3 | 揀貨員回報實揀數 | `LocationStock` 遞減，`PickTask` → `PICKED` | — |
| 4 | 全部 `PickTask` 皆 `PICKED` | `Shipment` → `DEPARTED` | **`ShipmentDeparted`** |

第 4 步的事件由訂單層消費，執行 `StockPool.consume()` 與 reservation → `CONSUMED`。
契約定義於 [system-layer-map.md 交會點 2](system-layer-map.md)。

### 取位規則

一張出貨單要 20 個 SKU-A，而該貨主的 SKU-A 分散在三個儲位（40／35／25），要產生幾個
`PickTask`、各自去哪個儲位取幾個：

| 順位 | 規則 | 理由 |
| --- | --- | --- |
| 1 | 優先能單一儲位滿足的 | 少一次移動 |
| 2 | 否則取量最多的儲位優先 | 減少 `PickTask` 數量 |
| 3 | 同量時取 `code` 字典序較小的 | 結果可重現，測試才寫得出來 |

第 3 條是為了**決定性**。沒有它，同樣的輸入可能產生不同的揀貨單，整合測試無法斷言。

最小版**不做揀貨動線排序**（W1 不解析階層）。動線排序是規則而非演算法，展示價值低，
且它是純新增，升級時再補。

---

## 短揀（W7）

系統記錄 `A-01-03-02-04` 上有某貨主的 10 個 SKU-A，揀貨員只找到 8 個。

### 處理鏈

| # | 層 | 動作 |
| --- | --- | --- |
| 1 | 履約層 | `PickTask.pickedQty = 8`，狀態 → `SHORT_PICKED` |
| 2 | 履約層 | `LocationStock` 修正為**實際值** |
| 3 | 履約層 | 發 `ShortPickDetected(ownerId, nodeId, sku, expectedQty, actualQty)` |
| 4 | 訂單層 | 修正 `StockPool.onHandQuantity`，對帳等式恢復 |
| 5 | 訂單層 ① | **整批退回重新決策**（採 ship-complete，不做部分出貨） |
| 6 | 訂單層 ③ | re-source 至他節點 |

第 2 步的細節容易做錯：`LocationStock` 不能用「原值減去實揀數」，因為原值本身就是
錯的。正確做法是設為揀貨員回報的實際剩餘量，或設為 0（全揀光仍不足）。

### 第 5 步的決策歸屬

「短揀之後怎麼辦」是**訂單層的決策**，不是履約層的。履約層只陳述事實（揀到 8 個），
由訂單層決定。

本專案採 ship-complete，所以那個決策的答案固定是「整批退回重新決策」——但**歸屬不因此
改變**。這個分工是驗證分層是否正確的試金石：若 `Shipment` 自己決定要怎麼處置短揀，
履約層就侵入了訂單層的職責。

（若日後改採 ship-partial，這一步才會出現「部分出貨 vs 整批退回」的實際分岔，而分岔點
仍在訂單層。）

### 為何短揀是必要而非選配

短揀是唯一將四件事串起來的場景：履約層發現帳差 → 庫存層修正邏輯帳 → 訂單層整批退回
重新決策 → ③ Sourcing 可能改由他節點出貨。

它同時運動到 ② 與 ③ 兩個演算法，且是在「計畫與現實不符」的條件下——比順利路徑更能
說明決策模型的設計。

若最小版只實作順利路徑，這一層在架構上只是 CRUD。

---

## 跨層交會點

四個交會點都存在，但第四個在最小版簡化。完整定義見
[system-layer-map.md](system-layer-map.md)。

| # | 交會點 | 最小版 |
| --- | --- | --- |
| 1 | 訂單配貨完成 → 產生揀貨任務 | 不變 |
| 2 | 出庫 → 邏輯帳扣減 | 不變，由 `ShipmentDeparted` 觸發 |
| 3 | 短揀 → 兩帳分歧的回饋 | 不變，最小版的核心 |
| 4 | 取消的補償路徑 | **由四種簡化為兩種** |

### 交會點 4 的簡化

| 取消時機 | 深做版 | 最小版 |
| --- | --- | --- |
| 未產生 `PickTask` | release | **release** |
| `PickTask` 未開始 | 取消 task，release | **取消 task，release** |
| 已揀貨、未離倉 | 須先回架，再 release | **不存在**——揀完即出貨 |
| 已離倉 | 不允許取消 | **不允許取消** |

---

## 要建立的檔案

`fulfillment` 為新增的 Gradle module，命名與邊界理由見
[system-layer-map.md](system-layer-map.md)。

### Usecase

| Usecase | 觸發 |
| --- | --- |
| `CreateShipmentUsecase` | 消費 `OrderAllocated` |
| `GeneratePickTasksUsecase` | 隨 `CreateShipment` 同交易 |
| `ConfirmPickUsecase` | 揀貨回報，含短揀分支；全部完成時發 `ShipmentDeparted` |
| `CancelShipmentUsecase` | 消費 `OrderCancelled` |
| `ListPickTasksUsecase` | 查詢，操作台用 |
| `GetLocationStockUsecase` | 查詢，操作台用 |

共 6 支，其中 2 支是查詢。

### 資料表

| 表 | 說明 |
| --- | --- |
| `locations` | 儲位主檔，`(node_id, code)` unique |
| `location_stock` | `(location_id, owner_id, sku_code, in_date, expiry_date)` 複合主鍵，含 `version` 樂觀鎖——身分必須與 `stock_pools` 一致，否則對帳等式不成立 |
| `shipments` | 出貨單，兩態 |
| `pick_tasks` | 揀貨任務，含 `order_line_id` |

### 事件

| 事件 | 方向 | 消費者 |
| --- | --- | --- |
| `OrderAllocated` | 入 | 履約層 |
| `OrderCancelled` | 入 | 履約層 |
| `ShipmentDeparted` | 出 | 訂單層，觸發 `consume()` |
| `ShortPickDetected` | 出 | 訂單層，修正邏輯帳 |
| `ShipmentCancelled` | 出 | 訂單層，觸發 `release()` |

沿用既有的 outbox 與 inbox 機制（`V5__create_event_inbox_and_outbox.sql`）。

### 邊界規則

`fulfillment` module **不得依賴** `order-promising`。所有跨層通訊經 Kafka 事件，
不直接 import `Order`、`StockPool` 或其 repository。

此規則由 Gradle module 邊界在編譯期強制。本專案已有反例：
`OrderAllocationCoordinator:29` 注入 `OrderRepository`、`AllocationService:58` 呼叫
`order.markAllocated()`——皆因同在一個 module，package 邊界擋不住。

---

## Seed 資料

| 項目 | 數量 | 理由 |
| --- | --- | --- |
| 儲位 | 每節點 3～4 個 | 少於 3 個無法展示「同 SKU 分散多儲位」的取位規則 |
| 分散情境 | 1 | 一個 SKU 分散三個儲位，且總量剛好不足以滿足某張單 |
| **帳差情境** | 1 | 預先埋一筆 `LocationStock` 與 `StockPool` 不符的資料，供短揀展示 |

W3 上架不做，儲位庫存由 seed 直接建立。連帶：既有的
`ReplenishmentUsecase` 直接呼叫 `StockPool.replenish()` 會**破壞對帳等式**——邏輯帳
增加了，實體帳沒有對應儲位。處理方式是讓它同時寫入 `LocationStock`，保留 demo 探針
的價值。這是履約層對訂單層唯一的反向影響。

---

## 對 Demo 操作台的影響

新增一頁：

| 區塊 | 內容 |
| --- | --- |
| 待揀任務清單 | 出貨單、儲位、貨主、SKU、應揀數 |
| 回報實揀數 | 輸入框 |
| **刻意短揀的按鈕** | 一鍵回報少於應揀數 |
| **對帳差異顯示** | `LocationStock` 總和 vs `StockPool.onHand`，不符時標示 |

最後兩項是這一頁最有價值的控制項——它們是唯一能讓觀看者看見兩本帳、以及看見帳差
如何被偵測與修正的方式。若操作台只能走順利路徑，W7 做了也展示不出來。

---

## 明確不做

| 項目 | 理由 |
| --- | --- |
| 收貨上架（W3） | seed 直接建立儲位庫存 |
| 複核裝箱（W8） | 需要 SKU 材積，而 SKU 主檔不存在 |
| 回架 putback（W10） | 「已揀未出」窗口在最小版不存在 |
| 盤點任務（W11） | 短揀只修正帳，不產生後續任務 |
| 揀貨動線排序 | 純新增，升級時再補 |
| 波次 Wave（W12） | demo 規模看不出合併效益 |
| 庫內移動、併板（W13） | 需要揀貨位／儲存位分離 |
| 補貨策略（W14） | 同上 |
| 批號、效期、序號（W15） | 不影響揀貨與出庫的正確性 |
| WES／WCS、設備控制（W16） | 履約層止於指令 |
| YMS 月台調度（W17） | 同層獨立系統 |
| 人員績效（W18） | 與領域正確性無關 |
| 出貨後的配送追蹤 | 屬 TMS，系統終點為 `DEPARTED` |
| 退貨驗收、上架回池 | 屬 RMS，連帶要求 `DEPARTED` 後不允許取消 |

上表中 W3、W8、W10、W11 與揀貨動線排序皆為**純新增**，升級時不動既有結構，詳見
[fulfillment-full-scope.md](fulfillment-full-scope.md)。
