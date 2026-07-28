# 系統分層地圖與範圍界定

狀態：分析，未確認

日期：2026-07-26

## 這份文件的角色

本文件界定本系統在完整供應鏈流程中的位置與深度，並定義兩個深做層之間的交會點。
各層細節由五份下層文件承載：

| 文件 | 涵蓋 |
| --- | --- |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | 訂單層 ① 收單編排、訂單與商品資料模型、貨主 |
| [dom-promising-scope.md](dom-promising-scope.md) | 訂單層 ② Promising：批次庫存模型與 FEFO 配貨演算法 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | 訂單層 ③ Sourcing/Routing。**已移出範圍**（2026-07-29），保留為分析記錄 |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | 履約層（最小版）：兩本帳與短揀對帳。**這是目前選定的範圍** |
| [fulfillment-full-scope.md](fulfillment-full-scope.md) | 履約層（深做版）：從最小版的增量。**尚未決定執行** |

跨層的契約、狀態機對照、帳務交會點與用詞決定，定義在本文件，五份下層文件均不重複
定義。

## 兩個貫穿全域的前提

**本系統是 3PL（第三方物流）**：倉庫不擁有貨，貨屬於委託的貨主。同一個 SKU 屬於
不同貨主就是不同的庫存，兩者不可互相調用。此前提決定了所有庫存的 key 都必須含
`ownerId`，見「兩本帳的分工」。

**所有訂單都是倉出**。曾評估過直送（`ship_from` 由貨主指定非倉庫來源），結論是它會
讓 ①②③ 與履約層各多一個分支，而該分支上沒有配貨決策、選點或揀貨，投入產出比不
成立，已排除。`fulfillment_node_id`（貨主指定從哪個倉出）保留，那仍是倉出流程。

## 完整流程與本系統的深度

```text
規劃層    Demand Planning                    需求預測／安全庫存水位              ✗ 不做
   ▼
商務層    Composable Commerce / MACH         目錄、購物車、結帳、金流            ✗ 上游
   ▼
訂單層    DOM                                ① 收單編排 ② Promising              ◆ 深做
                                             ③ Sourcing／Routing                 ✗ 外包給上游
   ▼
庫存層    IMS                                全網即時庫存視圖                    ◐ 內建
   ▼
採購/入庫  Procurement → ASN → Receiving      商品怎麼進來                        ○ 事件過場
   ▼
履約層    WMS → WES → WCS                    儲位／揀貨（兩本帳與短揀對帳）      ◐ 最小
   ▼
運輸層    TMS                                選物流商、路由、送達追蹤            ✗ 不做
   ▼
逆物流    RMS                                驗退、上架回池                      ✗ 不做
```

| 記號 | 意義 |
| --- | --- |
| ◆ 深做 | 完整領域模型、狀態機、測試 |
| ◐ 內建 | 不是獨立層，但因上下兩層都依賴它而必須做對 |
| ◐ 最小 | 只保留該層唯一不與他層同構的問題，其餘排除 |
| ○ 事件過場 | 只有狀態轉換與事件，無領域邏輯 |
| ✗ | 不在範圍內 |

系統終點：**貨離開倉庫、交給承運商**。不追蹤配送過程，不處理退貨。

## 庫存層為何是「內建」而非「過場」

原始範圍設想是「中間的庫存層以簡單事件與狀態轉換帶過」。此設想不成立，因為庫存層
正好是兩個深做層的交會點：

| 誰需要它 | 需要什麼 |
| --- | --- |
| 訂單層 ② Promising | 分倉分貨主的 ATP。倉別由上游指定，② 只在該倉的庫存裡配 |
| 履約層 | 實體帳（儲位庫存）必須與邏輯帳對得上 |

上下兩層都要用它，中間不可能簡單。但「不簡單」不等於要做完整 IMS——現行 `StockPool` 已在
扮演這個角色，需要的是把它做對，不是新增一層。

### 兩本帳的分工

| | `StockPool` | `LocationStock` |
| --- | --- | --- |
| 粒度 | `(owner, node, skuCode, expireDate, group)` 批次層 | `(location, owner, skuCode, expireDate, group)` 儲位×批次層 |
| 回答 | 這個批次還能承諾多少？ | 這個批次實際放在哪一格？ |
| 性質 | 邏輯帳 | 實體帳 |
| 維護者 | 訂單層 | 履約層 |

```text
node（北倉）· A貨主 · SKU-A
  ├── StockPool(效期 2026-08-01, GOOD) = onHand 60, reserved 30
  │     └── LocationStock (A-01-02) = 40
  │         LocationStock (A-03-01) = 20
  │                            合計 60 ── 必須等於上面的 onHand
  │
  ├── StockPool(效期 2026-09-15, GOOD)    = onHand 40  ← 同 SKU，不同批次
  └── StockPool(效期 2026-08-01, DAMAGED) = onHand  5  ← 同批次，不良品

node（北倉）· B貨主 · SKU-A
  └── StockPool(...)                                   ← 同 SKU，不同貨主
                                                          與 A 貨主不可互相調用
```

**對帳等式**：同一批次（`owner, node, skuCode, expireDate, group` 五個維度全同）的
所有儲位實體量總和，必須等於該筆 `StockPool.onHand`。短揀就是這個等式破掉的時候。

**兩本帳的維度必須一致**，否則等式無從成立——這是 `LocationStock` 也要帶效期與良品
狀態的理由，不只是為了知道揀哪一批。

**兩本帳都必須含貨主。** `LocationStock` 少了 `ownerId`，儲位上就分不出哪些貨是誰
的，回架與盤點都無從對應。這在 3PL 是資料事故等級的缺陷，不是選配。

IMS 所稱的「全網視圖」是對所有 `StockPool` 的聚合查詢，不是第三張表。

`StockPool` 目前只有 `sku`，沒有貨主、節點、效期或良品狀態。那是待修的缺陷，不是
設計選擇。四個維度應在同一次 migration 補齊，見
[dom-promising-scope.md](dom-promising-scope.md)。

### 設計原則：同一事實只記一處

已揀未出的貨**不在 `StockPool` 上額外記狀態**。它本來就已經 `reserved`，不在 ATP
裡，增設 `picked` 欄位不提供任何新資訊，只製造兩處可能不一致。需要「揀到哪了」時讀
`PickTask` 狀態。

## 跨層交會點

### 交會點 1：訂單配貨完成 → 產生揀貨任務

```text
[訂單層] OrderAllocated (+ownerId, +nodeId, +批次清單) ──Kafka──▶ [履約層] 建立 Shipment 與 PickTask
```

`nodeId` 由**貨主在上游下單時指定**（不是系統選的，見「為什麼不做 ③」），履約層據此知道
要在哪個節點揀貨。`ownerId` 決定要揀哪個貨主的貨——同節點同 SKU 可能有多個貨主的庫存，
少了它會揀錯貨。

**批次清單**由 ② 的 FEFO 配對產生。一個 order line 可能吃到多個批次，因此事件帶的是
清單而非單值，履約層據此產生對應的 `PickTask`。

### 交會點 2：出庫 → 邏輯帳扣減

`StockPool.onHandQuantity` 目前**只有 `replenish()` 一條遞增路徑，沒有任何遞減路徑**。
`release()` 只動 `reservedQuantity`（取消退回）。系統的貨從未真正出去過。

```text
[履約層] ShipmentDeparted ──Kafka──▶ [訂單層] StockPool.consume()
                                              onHand -= q 且 reserved -= q
                                              Reservation → CONSUMED
```

| 缺 | 內容 |
| --- | --- |
| `StockPool.consume(int)` | onHand 與 reserved 同步遞減 |
| `ReservationStatus.CONSUMED` | 與 `RELEASED`（取消退回）語意分離 |

**扣帳時機採「離倉時扣」而非「揀貨時扣」。** 揀貨後貨仍在倉庫內，物理上未離開；
出貨區的貨在取消時仍可回架。以離倉為界，`onHand` 的語意始終是「這個節點倉庫裡實際
有的量」，不需要任何中間狀態。

### 交會點 3：短揀 —— 兩帳分歧的回饋路徑

系統記錄儲位上有 10 個，揀貨員只找到 8 個。這是唯一將四件事串起來的場景：

| 觸發 | 層 | 動作 |
| --- | --- | --- |
| 實揀數 < 應揀數 | 履約層 | `PickTask` 記錄短揀，修正 `LocationStock` |
| 邏輯帳失真 | 庫存 | 修正 `StockPool.onHandQuantity` |
| 訂單無法足額履約 | 訂單層 ① | **整批退回重新決策**。採 ship-complete，不做部分出貨 |
| 需改由他倉出貨 | 上游 | 由貨主重新指定倉別後重下單。系統不做 re-source（③ 已移出範圍） |
| 帳差需人工確認 | 履約層 | 產生盤點任務（**最小版不做**，見 [fulfillment-full-scope.md](fulfillment-full-scope.md) F7） |

若履約層只實作順利路徑，該層在架構上只是 CRUD。短揀是驗證分層設計是否成立的關鍵
場景，應在履約層的第一個 change 就納入，不列為選配。

### 交會點 4：取消的補償路徑分岔

| 取消時機 | 補償 | 最小版 |
| --- | --- | --- |
| 未產生 `PickTask` | `StockPool.release()`，reservation → `RELEASED` | ✓ |
| `PickTask` 未開始 | 取消 `PickTask`，同上 | ✓ |
| 已揀貨、未離倉 | **須先回架（putback）**，`LocationStock` 復原後才 release | **不存在**——最小版揀貨確認即出貨，無此窗口 |
| 已離倉 | **不允許取消**。逆物流不在範圍內，此路徑無補償手段 | ✓ |

最後一列須在狀態機上明確禁止，否則會出現無法補償的路徑。

最小版因此只有兩種補償路徑。回架是「物理動作的補償是另一個物理動作，不是資料回滾」
的最好例子，也是最小版最實質的損失，見
[fulfillment-full-scope.md](fulfillment-full-scope.md) F5。

## 狀態機對照

三個聚合各有自己的生命週期，終態使用不同詞彙以避免混淆：

| 聚合 | 層 | 狀態 |
| --- | --- | --- |
| `Order` | 訂單層 | `PENDING` → `ALLOCATED` → `FULFILLED`；分支 `BACKORDERED`、`CANCELLED`。**沒有 `PARTIALLY_ALLOCATED`**——採 ship-complete，整單全有全無，見 [dom-promising-scope.md](dom-promising-scope.md) |
| `Shipment` | 履約層 | 最小版兩態：`CREATED` → `DEPARTED`；深做版補 `PICKING` → `PICKED` → `PACKED` |
| `StockReservation` | 訂單層 | `ACTIVE` → `RELEASED`（取消退回）或 `CONSUMED`（出貨用掉） |

### 用詞決定

| 決定 | 理由 |
| --- | --- |
| Shipment 終態用 `DEPARTED` | 純粹描述「離開這個倉庫」，不宣告承運商做了什麼，也不暗示送達。且與 `PICKED` / `PACKED` 同為過去分詞、同樣描述倉庫已完成的事實，語法風格一致 |
| 不用 `SHIPPED` | 易被讀為「已送達」。系統不做 TMS，其後沒有 `DELIVERED` 可供對比澄清語意 |
| 不用 `DISPATCHED` / `HANDED_OVER` / `TENDERED` | 前者宣告了承運商才能宣告的事；後二者描述「我與他人之間發生了什麼」，跳出了狀態機的語法線 |
| 不用 `PICKED_UP` | 與 `PICKED` 在同一狀態機內嚴重混淆 |
| Order 終態用 `FULFILLED`，不與 `DEPARTED` 共用 | 兩者是不同事實。拆單後一張 Order 對多個 Shipment，**全部 Shipment 皆 `DEPARTED` 時 Order 才 `FULFILLED`**，此關係需要兩個詞才能表達 |
| Reservation 終態用 `CONSUMED` | 描述的主體是**預留額度**而非庫存——額度本來就是拿來用掉的。與 `RELEASED` 形成「用掉 vs 還回去」的清楚對比，且不與另兩個聚合的終態撞名 |

保留 `DELIVERED` 一詞不用。若日後接入 TMS，該詞有位置可放，不需回頭改語意。
若日後加入集貨區作業，`STAGED` 可插入 `PACKED` 與 `DEPARTED` 之間，風格一致。

## 演算法定位

供應鏈各層都有最佳化問題，但分屬不同性質。釐清位置可避免把不在範圍內的演算法當成
必要工作。

| 演算法 | 所在層 | 問題類型 | 本系統 |
| --- | --- | --- | --- |
| 需求預測、安全庫存水位 | 規劃層 | 時間序列預測、`(s,S)` policy、EOQ | **不做**——不做規劃層 |
| 配貨優先序（外層：哪些**訂單**先配） | 訂單層 ② | knapsack 變形 | **已實作** |
| **批次配對 FEFO**（內層：吃哪些**批次**） | 訂單層 ② | 硬約束篩選 + 排序取用 | **要做** |
| 節點選擇 | 訂單層 ③ | assignment / transportation | **不做**——見下方「為什麼不做 ③」 |
| 揀貨路徑 | 履約層 | TSP 變形 | 以儲位排序規則替代（最小版不做） |
| 波次規劃 | 履約層 | bin packing / clustering | 不做 |
| **裝箱（cartonization）** | 履約層 | bin packing | **已識別，未排程**——見下方 |
| 上架儲位選擇 | 履約層 | 規則型，非最佳化 | 規則即可（最小版不做） |

三點說明：

**「supply-demand algorithm」通常指第一列**，它在規劃層，不在本系統範圍。需求預測
需要歷史資料才有意義，demo 環境沒有歷史，實作出來只會是虛構的。

**第二、三列是巢狀的兩層。** 外層決定「誰有資格拿」——`MaximizeFulfilledOrdersPolicy`
與 `StrictFifoAllocationPolicy` 是同一問題的兩種策略，`AllocationPolicy<C>` 抽象即為
此而存在。內層決定「拿到的是哪一批」——先以 `group` 與允售天數篩掉不可售批次，再依
效期由近到遠取用（FEFO）。兩層合起來才是完整的 supply-demand allocation。

**第四列以規則替代最佳化。** 依 `zone → aisle → level` 排序揀貨清單是多數 WMS 的
實務做法，已達成主要效果，且可解釋、可展示、零調參。真 TSP 在 demo 中看不出差別，
卻要引入 solver 依賴。

### 為什麼不做 ③ Sourcing／Routing

2026-07-29 決定移出範圍。理由不是規模，是**這一層在 3PL 裡沒有決策**。

3PL 的五個決策裡只有兩個是系統算的：

| 決策 | 誰決定 |
| --- | --- |
| 從哪個倉出 | 上游／合約——貨主下單時就指定倉別 |
| 送到哪裡 | 上游 |
| 誰來送 | 上游（TMS 本來就不做） |
| **配哪批貨** | **系統**——② Promising 的 FEFO |
| **裝幾箱** | **系統**——履約層，見下 |

貨主指定倉別、系統照做。**沒有選擇就沒有選點問題**——成本函數、覆蓋範圍、節點能力比對
全部沒有輸入來源。硬做出來會是一個沒有人使用的決策，展示時只能靠調權重看數字跳動。

**倉庫這個維度保留**，砍掉的只是「系統選倉」。一個貨主可以有多個倉（多對多），但一張訂單
只能一個倉、明細不可跨倉——這是上游系統的既有規則。相關護欄見
[execution-roadmap.md](execution-roadmap.md) 的「為什麼沒有 R6」。

`Routing` 一詞在此不再出現。它在 DOM 的語意是「選出貨節點」，那件事已外包給上游；另外三種
常見語意（配送路線規劃、物流商選擇、揀貨路徑）分屬 TMS 與 WMS，本來就不在範圍。

### 裝箱：已識別但未排程

裝箱（cartonization）是**系統實際會算的第二個演算法**，屬履約層的包裝作業。它不在目前選定的
最小版（兩本帳與短揀對帳）裡。

記在這裡是為了避免一個具體的誤讀：上面的表把 bin packing 標成「不做」，那是針對**波次規劃**
說的，而該決定是在不知道裝箱也在系統職責內的情況下做的。要做裝箱就是把履約層從最小版往
深做版移一格，屬獨立的範圍決定。

DOM 也有裝箱的變體（出貨前預估箱數以估運費、挑物流商），**本系統不適用**——運費與物流商都由
上游決定。

**履約層做最小版或深做版，會影響演算法的量體。** 兩個標註「最小版不做」的項目都是規則而非
演算法，但**裝箱是演算法**且屬履約層——③ 移出範圍後，深做與否決定了本系統有一個還是兩個
演算法。supply-demand 本身的量體則完全由 ② 決定。

## Module 結構

| Module | 內容 | 狀態 |
| --- | --- | --- |
| `order-promising` | `ordering`、`allocation`、`catalog`、`common`、`demo`、`bootstrap` | 已存在 |
| `fulfillment` | 履約層（最小版：兩本帳與短揀對帳） | **新增** |

### 為何是獨立 module 而非新 package

| | package | Gradle module |
| --- | --- | --- |
| 邊界強制力 | 靠慣例與 review | **編譯期強制** |
| 能否防止履約層直接 import `Order` | 否 | 是 |

本專案已有前例：`OrderAllocationCoordinator:29` 注入 `OrderRepository`、
`AllocationService:58` 呼叫 `order.markAllocated()`——兩者都是因為 `ordering` 與
`allocation` 同在一個 module，package 邊界擋不住。履約層量級更大，同樣的錯誤更難
回頭。另外 `order-promising` 這個 module 名稱已界定範圍，將履約層納入會使名稱失效。

仍為**單一 Spring Boot 應用**，由 `bootstrap` 同時依賴兩個 module。這不是「先合併
之後再拆服務」的過渡安排——module 邊界已提供拆分所需的全部準備，是否拆為獨立部署
單元屬於部署決策，不是設計決策。

### 為何叫 `fulfillment` 而非 `wms` 或 `warehouse`

本系統只做 WMS 的一部分：儲位、上架、揀貨、裝箱、出庫。不做 WES／WCS、設備控制、
YMS、庫內移動、補貨策略。

| 候選 | 判定 |
| --- | --- |
| `fulfillment` | **採用**。語意剛好是「把已配貨的訂單變成實際出貨」；與 DOM 分工清楚（DOM 決策、fulfillment 執行）；且與既有 `order-promising` 同為**能力導向**命名 |
| `warehouse` | 偏廣，暗示涵蓋庫內全部作業 |
| `wms` | 承諾過大，且是系統導向、與既有命名風格不一致 |
| `outbound` | 語意精準，但 putaway（上架）屬入庫作業，會被名稱排除——而沒有 putaway 就沒有 `LocationStock` |

文件中仍以「履約層／WMS 範疇」對應產業分層，但 module 與 package 一律用
`fulfillment`。

## 執行順序

```text
第一步  資料模型定形 ─┬──▶ ①D 收單冪等
（見下）              └──▶ ①C 放寬多筆 line
①A 編排權歸位 ────────────────────────────┐
                                          │
交會點 2（consume）───────────────────────┤  履約層的前置
                                          ▼
                              履約層（最小版）兩本帳與短揀
```

### 第一步：資料模型定形

> **排程以 [execution-roadmap.md](execution-roadmap.md) 為準。** 本節原先主張下列六項
> 「必須在同一次 migration 完成」，roadmap 已把它切成 R1／R2／R3 三個 change 並
> **刻意接受**中間狀態。roadmap 較新且說明更完整，以它為準；本節僅保留「哪些內容屬於
> 資料模型定形」這份清單，不再主張它們同批。

| 內容 | 來源 | roadmap 的 change |
| --- | --- | --- |
| `owners`、`products`、`skus` 主檔 | ① 段 E | **R1** |
| `order_lines` 建表（每張單先只有一筆） | ① 段 C 的結構部分 | **R1** |
| `fulfillment_nodes` 極簡主檔（無覆蓋、無能力、無成本） | 倉別由上游指定 | **R2** |
| `stock_pools` key 加 `owner_id` | ① 段 E | **R3** |
| `stock_pools` key 加 `node_id` | ③ | **R3** |
| `stock_pools` key 加 `expire_date`、`group` | ② P1 | **R3** |
| `stock_reservations` FK 改為 `order_line_id` ＋ 帶批次 | ② P1 | **R3** |

**`stock_pools` 的四個維度仍必須在同一次 migration（R3）完成**——它們動的是同一組
unique constraint 與同一批查詢，分次做等於改四輪。這一點 roadmap 與本節一致。

分歧只在 `owners`／`products`／`skus`／`order_lines` 是否要與 `stock_pools` 同批。
roadmap 判定不必，理由是 R1 完成後「下單 → 查詢 → 取消」可在含貨主與 line 的模型下
先驗過一輪，而跨貨主隔離的缺口是**明確標註的已知限制**，不是被忽略的錯誤。本節原先
寫「中間狀態沒有任何價值」時，沒有把「可分段驗證」算進來。

第一步完成後接上 FEFO 配對邏輯（② 的內層演算法），**此時 supply-demand allocation
即可完整展示**——不需要 ③ 的節點選擇（那已移出範圍，且單節點也有多批次競爭），也不需要 ①A。

### 其餘各段

| 段 | 依賴 | 理由 |
| --- | --- | --- |
| ①A 編排權歸位 | 無 | 純重構，但與第一步都要改 `AllocationService`，**建議串行**避免互相踩到 |
| 交會點 2 consume | 無 | 既有 ② 就該有的閉環，可與 ①A 並行；為履約層的前置 |
| ①D 冪等 | 第一步 | 冪等鍵是 `(owner_id, external_order_no)` |
| ①C 放寬多筆 line | 第一步 | `order_lines` 已存在，此段只是放寬「一張單可有多筆」，非搬遷結構 |
| 履約層（最小版） | 第一步、交會點 2 | 無 `nodeId` 則不知在哪揀貨；無 `ownerId` 與批次則揀錯貨；無 `consume()` 則出庫無法扣帳。`nodeId` 由上游指定，不需要 ③ |

## 明確不做

| 項目 | 歸屬 | 說明 |
| --- | --- | --- |
| 需求預測、安全庫存水位 | 規劃層 | demo 環境無歷史資料 |
| 金流、發票、稅務、風控 | 商務層 | 本系統不從客戶端收錢 |
| 目錄、購物車、結帳 | 商務層 | 訂單抵達時已成形 |
| 採購單管理 | 採購層 | 以事件鏈過場 |
| 物流商選擇、比價、booking | 運輸層 TMS | Routing 在 DOM 指「選出貨節點」，非「規劃配送路線」 |
| **選出貨節點（③ Sourcing）** | 訂單層 ③ | **2026-07-29 移出**。3PL 裡由合約與上游決定，見「為什麼不做 ③」 |
| 車輛排程、送達追蹤 | 運輸層 TMS | 同上 |
| 驗退、上架回池 | 逆物流 RMS | 連帶要求：`DEPARTED` 後不允許取消 |
| WES／WCS、設備整合 | 執行層 | 履約層止於指令，不含設備控制 |
| YMS 月台與拖車調度 | 同層獨立系統 | — |