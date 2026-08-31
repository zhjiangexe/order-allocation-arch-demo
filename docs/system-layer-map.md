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
成立，已排除。`facility_id`（貨主指定從哪個倉出）保留，那仍是倉出流程。

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

上下兩層都要用它，中間不可能簡單。但「不簡單」不等於要做完整 IMS——現行 `StockQuant` 已在
扮演這個角色，需要的是把它做對，不是新增一層。

### 一本帳，兩種粒度的問法

**這一節原本寫的是「兩本帳」**——邏輯帳 `StockQuant` 與實體帳 `LocationStock`，中間靠一條
對帳等式維繫。庫存異動模型交付之後那個框架不成立了：只有一本帳。

| | `stock_pools` |
| --- | --- |
| 粒度 | `(owner, location, sku, in_date, expiry)` |
| 回答 | 這一批貨放在哪、還能承諾多少 |
| 維護者 | 庫存執行層（`inventory.position` / `inventory.movement` / `inventory.reservation`），而且**只能由搬運的明細改** |

位置本身分層：現在是「一個 Facility 可有多個平面 internal locations」，R7 讓它長出
`parent_id` 之後，庫存列仍掛在
**儲位**上。於是同一張表回答兩種問題：

```text
北倉 · A貨主 · SKU-A · 效期 2026-08-01
  ├── 儲位 A-01-02  onHand 40, reserved 20
  └── 儲位 A-03-01  onHand 20, reserved 10
       這個批次在這個倉還能承諾多少？ → 對子樹加總
       這個批次實際放在哪一格？       → 直接讀那一列
```

**沒有對帳等式，因為沒有第二本帳可對。** 短揀仍然是真實的——帳上 10 個、只揀到 8 個——但
它的修正方式是**再記一段搬運**（盤點調整），而不是「修其中一本帳讓兩本對齊」。

這是 Odoo 的形狀：quant 掛在最細的位置上，而
`_get_available_quantity(..., strict=False)` 對子樹加總。它沒有第二張表。

> **連帶：配貨查詢要改成子樹比對。** 現在是 `location_id = <倉的內部位置>` 的等值篩選；
> 儲位化之後要變成「這個倉底下的所有位置」。Odoo 用 `parent_path LIKE` 做這件事。這是
> R7 開工時要一併處理的，不是事後補。

**貨主必須在鍵裡。** 少了 `owner_id`，儲位上就分不出哪些貨是誰的，回架與盤點都無從對應。
這在 3PL 是資料事故等級的缺陷，不是選配。

IMS 所稱的「全網視圖」是對庫存列的聚合查詢，不是第三張表。

### 設計原則：同一事實只記一處

已揀未出的貨**不在 `StockQuant` 上額外記狀態**。它本來就已經 `reserved`，不在 ATP
裡，增設 `picked` 欄位不提供任何新資訊，只製造兩處可能不一致。需要「揀到哪了」時讀
`PickTask` 狀態。

## 跨層交會點

### 交會點 1：訂單配貨完成 → 產生揀貨任務

```text
[訂單層] OrderAllocated (+ownerId, +facilityId, +批次清單) ──Kafka──▶ [履約層] 建立 Shipment 與 PickTask
```

`facilityId` 由**貨主在上游下單時指定**（不是系統選的，見「為什麼不做 ③」），履約層據此知道
要在哪個節點揀貨。`ownerId` 決定要揀哪個貨主的貨——同節點同 SKU 可能有多個貨主的庫存，
少了它會揀錯貨。

**批次清單**由 ② 的 FEFO 配對產生。一個 order line 可能吃到多個批次，因此事件帶的是
清單而非單值，履約層據此產生對應的 `PickTask`。

### 交會點 2：出庫 → 在庫量遞減

在庫量目前**只有遞增路徑，沒有任何遞減路徑**——`release()` 只動 `reservedQuantity`
（取消退回）。系統的貨從未真正出去過。

```text
[履約層] ShipmentDeparted ──Kafka──▶ [stock] 未來的出庫扣帳 use case 完成那段出庫搬運
                                              由明細扣掉 onHand 與 reserved
                                              搬運 → DONE
```

| 缺 | 內容 |
| --- | --- |
| 正式的出庫扣帳 use case | 尚未實作；未來消費 `ShipmentDeparted`，完成實際出庫並扣除庫存投影 |
| 扣帳憑證 | 必須來自實際出貨事實與搬運明細，不能只收一個無來源數字 |

**扣帳時機採「離倉時扣」而非「揀貨時扣」。** 揀貨後貨仍在倉庫內，物理上未離開；
出貨區的貨在取消時仍可回架。以離倉為界，`onHand` 的語意始終是「這個節點倉庫裡實際
有的量」，不需要任何中間狀態。

### 交會點 3：短揀 —— 兩帳分歧的回饋路徑

系統記錄儲位上有 10 個，揀貨員只找到 8 個。這是唯一將四件事串起來的場景。

**修正的方式是再記一段搬運，不是改數字**——那正是「在庫量只能由搬運改」這條不變式在異常
路徑上的樣子。少掉的 2 個去了 `INVENTORY` 這個虛擬位置，而它至今沒有讀者，等的就是這裡。

| 觸發 | 層 | 動作 |
| --- | --- | --- |
| 實揀數 < 應揀數 | 履約層 | `PickTask` 記錄短揀並發事實 |
| 帳與實物不符 | stock | **再記一段搬運**（盤點調整：庫存 → `INVENTORY` 虛擬位置）把在庫量修正到實際值 |
| 訂單無法足額履約 | 訂單層 ① | **整批退回重新決策**。採 ship-complete，不做部分出貨 |
| 需改由他倉出貨 | 上游 | 由貨主重新指定倉別後重下單。系統不做 re-source（③ 已移出範圍） |
| 帳差需人工確認 | 履約層 | 產生盤點任務（**最小版不做**，見 [fulfillment-full-scope.md](fulfillment-full-scope.md) F7） |

若履約層只實作順利路徑，該層在架構上只是 CRUD。短揀是驗證分層設計是否成立的關鍵
場景，應在履約層的第一個 change 就納入，不列為選配。

### 交會點 4：取消的補償路徑分岔

| 取消時機 | 補償 | 最小版 |
| --- | --- | --- |
| 未產生 `PickTask` | `AllocationReservationCanceller`：取消搬運、刪除明細、把量還給庫存 | ✓ |
| `PickTask` 未開始 | 取消 `PickTask`，同上 | ✓ |
| 已揀貨、未離倉 | **須先回架（putback）**，貨回到儲位後才取消搬運 | **不存在**——最小版揀貨確認即出貨，無此窗口 |
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
| `StockMove` | stock | `CONFIRMED`（要貨）→ `ASSIGNED`（鎖到貨）→ `DONE`（貨真的動了）；分支 `CANCELLED`。**沒有 `WAITING` 也沒有 `PARTIALLY_AVAILABLE`**，理由見 `MoveState` 的 javadoc |

### 用詞決定

| 決定 | 理由 |
| --- | --- |
| Shipment 終態用 `DEPARTED` | 純粹描述「離開這個倉庫」，不宣告承運商做了什麼，也不暗示送達。且與 `PICKED` / `PACKED` 同為過去分詞、同樣描述倉庫已完成的事實，語法風格一致 |
| 不用 `SHIPPED` | 易被讀為「已送達」。系統不做 TMS，其後沒有 `DELIVERED` 可供對比澄清語意 |
| 不用 `DISPATCHED` / `HANDED_OVER` / `TENDERED` | 前者宣告了承運商才能宣告的事；後二者描述「我與他人之間發生了什麼」，跳出了狀態機的語法線 |
| 不用 `PICKED_UP` | 與 `PICKED` 在同一狀態機內嚴重混淆 |
| Order 終態用 `FULFILLED`，不與 `DEPARTED` 共用 | 兩者是不同事實。拆單後一張 Order 對多個 Shipment，**全部 Shipment 皆 `DEPARTED` 時 Order 才 `FULFILLED`**，此關係需要兩個詞才能表達 |
| 搬運終態用 `DONE`，取消用 `CANCELLED` | 沿用 Odoo 的 `stock.move` 值域。曾經這裡是預留的 `CONSUMED` / `RELEASED`，而預留已經不是一個獨立的東西——它是搬運被鎖定的狀態，所以那組詞連同型別一起消失了 |

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

### 限量開賣（秒殺）：不做，且理由是位置而不是規模

本系統**不做 admission control**。限量開賣的閘門在銷售通路，不在這裡。

電商前台有自己的一份庫存快照擋量，3PL／WMS 才是真相來源，兩邊非同步對帳。所以到得了這個
系統的訂單已經被前台過濾過一輪——**這個系統的角色是最終裁判，不是閘門**。而前台那份快照與
真相會漂移，超賣正是這樣發生的，因此裁判這個角色不能省。

**分路的正當理由是語意不同，不是量大。** 秒殺與一般訂單要回答的問題不一樣：

| | 一般訂單 | 秒殺 |
| --- | --- | --- |
| 問題 | 配得到嗎？配不到就掛帳 | 我是前 N 名嗎？不是就立刻拒絕 |
| 缺貨 | 進 FIFO 佇列等補貨 | **沒有佇列**——會掛帳的秒殺是壞的秒殺 |
| 每筆的成本 | 驗證、查主檔、交易、發事件 | 一次原子遞減 |

純粹為了量而分出第二條路，會變成兩套實作同一條規則，然後它們分岔——而分岔的形式通常是超賣，
發生在只有一邊被修過的那條路上。

**outbox pattern 本身也不適合秒殺。** 它為了「本地狀態變更與事件發布的原子性」而每則事件寫一
列資料庫，再經 Debezium CDC 出去；秒殺要的是本地的、立即的「搶到／售完」，不需要跨服務整合的
可靠性，卻付得起不了那個延遲與寫入量。兩者解的是不同問題。

因此本系統對爆量的目標是**在爆量下仍然正確**（不超賣、不靜默丟單），不是承載閘門級的流量。
對應的機制是 partition key 的 single writer、樂觀鎖與版號、重試與 DLT，以及
`hot-sku-concurrency-demo` 把那個行為演出來。

### 倉別時區：已識別但未排程

效期以日期（而非瞬間）比對，因此「今天是幾號」必須挑一個時區來切。R3 收成
`BusinessClock` port；bootstrap 的 `ConfiguredBusinessClock` 依
`archone.business-zone` 設定時區，目前全系統一個值（預設 `Asia/Taipei`）。

**正確的模型是時區屬於倉庫**——東京倉的貨照東京的日曆過期，該放在
`facilities.time_zone`，而 `isSellable` 的判準隨批次所在的倉走。本專案的倉全在台灣，
現在做等於為想像中的需求先設計，因此不排程。

會踩到的條件很具體：**同一個貨主的倉跨越多個時區**。屆時全域設定會讓其中一邊每天有數小時
把已過期的貨判成可售，而且不會有任何錯誤浮現——貨就出去了。改動範圍是 `facilities`
加一欄、`BusinessClock` 改為依倉查詢。

## Module 結構

| Module | 內容 | 狀態 |
| --- | --- | --- |
| `ordering-context` | `ordering/{application,domain,entrypoint,infrastructure}` | 已抽離為獨立 Gradle module |
| `inventory-context` | `inventory/{allocation,balance,movement,warehouse}` | 已抽離為獨立 Gradle module |
| `logistics-data-context` | `logisticsdata/{application,domain,entrypoint,infrastructure}` | 貨主、商品、SKU 與倉別等物流主檔 context |
| `bootstrap` | Spring Boot 啟動、跨 context 組裝、migration 與 `demo` | 單一 deployable runtime |
| `fulfillment` | 履約層（最小版：兩本帳與短揀對帳） | **新增** |

`ordering-context`、`inventory-context` 與 `logistics-data-context` 都已取得編譯期 module 邊界，但尚未成為獨立微服務。
資料庫 migration、跨 context 的 adapter view 與 Spring Boot 啟動仍由 `bootstrap` 擁有；若日後需要
獨立部署，再分別建立 runtime module，並先以事件或外部 API 取代跨資料庫查詢。

單一 context 的 unit／MVC slice tests 跟著各自的 module；`inventory-context` 另外以 Gradle test fixtures
發布 context-owned 測試資料。需要同時組裝 Ordering、Inventory、migration 或 PostgreSQL 的測試才留在
`bootstrap`，避免測試 fixture 反向模糊 production module 邊界。

`inventory` 目前是同一 bounded context 的 package 根；`allocation` 負責 operation precedence、純供需規劃與批次選擇，
`balance` 負責 `StockQuant` 與收貨，`movement` 負責 `StockOperation`／`StockMove` 的意圖與生命週期紀錄，
`warehouse` 擁有 `StockLocation` 與 `StockOperationType` 倉儲設定。WMS 的 picking、wave 與 task execution 仍由
`wms-context` 擁有，不因 Inventory operation 命名而轉移責任。

餘額 aggregate 採 Odoo ubiquitous language 命名為 `StockQuant`。既有 PostgreSQL 表
`stock_pools`、欄位 `stock_pool_id`、`GET /stock-pool`、v1 JSON 的 `stockPoolId`，以及
integration aggregate type `StockPool` 暫時維持相容；Java domain 與 persistence adapter 內部則
統一使用 `StockQuant`／`stockQuantId`。這些外部識別若要改，必須另做 migration／API versioning，
不能混在單純的 model rename 裡。

### 為何是獨立 module 而非新 package

| | package | Gradle module |
| --- | --- | --- |
| 邊界強制力 | 靠慣例與 review | **編譯期強制** |
| 能否防止履約層直接 import `Order` | 否 | 是 |

本專案有過那個前例：配貨曾經注入 `OrderStore`、由 domain service 直接呼叫
`order.markAllocated()`——兩者都是因為 `ordering` 與執行層同在一個 module，package 邊界
擋不住。後來以事件斷開並補了架構測試，但那是**事後檢查**；module 邊界在編譯期就擋下來。
履約層量級更大，同樣的錯誤更難回頭。`bootstrap` 只是技術組裝層，不是可以容納任意 domain model 的共用業務 module；履約仍應擁有自己的 module 邊界。

仍為**單一 Spring Boot 應用**，由 `bootstrap` 組裝各 bounded context module。
這不是「先合併之後再拆服務」的過渡安排——module 邊界已提供拆分所需的基礎，是否拆為獨立部署
單元屬於部署決策，不是設計決策。

### 為何叫 `fulfillment` 而非 `wms` 或 `warehouse`

本系統只做 WMS 的一部分：儲位、上架、揀貨、裝箱、出庫。不做 WES／WCS、設備控制、
YMS、庫內移動、補貨策略。

| 候選 | 判定 |
| --- | --- |
| `fulfillment` | **採用**。語意剛好是「把已配貨的訂單變成實際出貨」；與 DOM 分工清楚（DOM 決策、fulfillment 執行）；並且是**能力導向**命名 |
| `warehouse` | 偏廣，暗示涵蓋庫內全部作業 |
| `wms` | 承諾過大，且是系統導向、與既有命名風格不一致 |
| `outbound` | 語意精準，但 putaway（上架）屬入庫作業，會被名稱排除——而沒有 putaway，貨就永遠只掛在倉層的位置上，進不到儲位 |

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
| `facilities` 極簡主檔（無覆蓋、無能力、無成本） | 倉別由上游指定 | **R2** |
| `stock_pools` key 加 `owner_id` | ① 段 E | **R3** |
| `stock_pools` key 加 `facility_id` | ③ | **R3** |
| `stock_pools` key 加 `in_date`、`expiry_date` | ② P1 | **R3** |
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
| 履約層（最小版） | 第一步、交會點 2 | 無 `facilityId` 則不知在哪揀貨；無 `ownerId` 與批次則揀錯貨；無 `consume()` 則出庫無法扣帳。`facilityId` 由上游指定，不需要 ③ |

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
