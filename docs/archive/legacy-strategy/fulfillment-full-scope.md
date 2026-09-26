# 履約層（深做版）：從最小版的增量

狀態：分析，未確認。**此版本尚未決定執行**

日期：2026-07-26

## 這份文件回答什麼

本文件**只寫增量**：從 [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md)
升級為完整履約層要加什麼，以及為何每一項都是純新增。

不重複最小版已定義的領域模型、跨層契約與邊界規則。閱讀本文件前應先讀最小版。

| 相關文件 | 涵蓋 |
| --- | --- |
| [fulfillment-minimal-scope.md](fulfillment-minimal-scope.md) | **基準版本**，本文件的所有增量都相對於它 |
| [execution-roadmap.md](execution-roadmap.md) | **八個 change 的順序、依賴、任務與禁忌** |
| [system-layer-map.md](system-layer-map.md) | 全流程分層、跨層契約、用詞決定 |
| [dom-order-intake-scope.md](dom-order-intake-scope.md) | 訂單層 ①、訂單與商品資料模型 |
| [dom-promising-scope.md](dom-promising-scope.md) | 訂單層 ②：批次庫存模型 |
| [dom-sourcing-scope.md](dom-sourcing-scope.md) | 訂單層 ③ |

## 為何寫成增量而非完整版本

兩份文件描述同一個層，若各自寫完整內容，領域模型、契約與邊界規則會有三處以上重複，
日後修改必然漂移。寫成增量後，**深做版永遠是「最小版 + delta」**，要升級時直接照
delta 執行。

---

## 增量總表

| # | 增量 | 類型 | 相依 |
| --- | --- | --- | --- |
| F1 | 儲位階層解析 | 欄位細化 | — |
| F2 | 揀貨動線排序 | 純新增 | F1 |
| F3 | 出貨單完整生命週期（2 態 → 5 態） | 狀態新增 | — |
| F4 | 複核裝箱 | 純新增 | F3 |
| F5 | 回架 putback | 純新增 | F3 |
| F6 | 收貨上架 | 純新增 | F1 |
| F7 | 盤點任務 | 純新增 | — |

**沒有任何一項需要搬遷既有結構。** 這是最小版刻意採用三處最終形態換來的，理由見
[fulfillment-minimal-scope.md 的「可升級性」](fulfillment-minimal-scope.md)。

| | 最小版 | 深做版 |
| --- | --- | --- |
| 資料表 | 4 | 7 |
| 聚合 | 3 | 4 |
| Usecase | 6 | 10 |
| `Shipment` 狀態 | 2 | 5 |
| 前端頁數 | 1 | 3 |
| 相對量 | 25% | 100% |

---

## F1：儲位階層解析

最小版的 `Location` 只有 `code`，格式為 `A-01-03-02-04` 但不解析。

```text
{zone}-{aisle}-{rack}-{level}-{bin}
   A  -  01  -  03  -  02  -  04
```

| 動作 | 內容 |
| --- | --- |
| `locations` 加欄位 | `zone`、`aisle`、`rack`、`level`、`bin`、`status` |
| 資料填充 | **由既有 `code` 拆解，不需重新編碼** |
| `Location.status` | `ACTIVE` / `BLOCKED`（儲位損壞時暫停使用） |

**這是最小版三處預防中最關鍵的一項。** 若最小版的 `code` 用了任意字串（如
`LOC-001`），此處要重新編碼所有儲位並重建 seed；用階層格式則只是把字串拆成欄位，
資料不動。

`Location.status` 是 F7 盤點與異常處理的前置——沒有它，發現儲位有問題時無處著力。

## F2：揀貨動線排序

`PickTask` 加 `sequence`，依儲位階層排序：

```sql
ORDER BY zone, aisle, level, bin
```

編碼即路徑：字典序排序後就是揀貨動線。

**這是規則，不是演算法。** 真實的揀貨路徑最佳化是 TSP 變形，但依儲位編碼排序已達成
主要效果（不折返、不跨區來回），且可解釋、可展示、零調參。真 TSP 需要引入 solver
依賴，而在 demo 規模下看不出差別。

演算法在各層的完整定位見 [system-layer-map.md](system-layer-map.md)。此項不改變
任何演算法的存在與否——最小版與深做版的 supply-demand 演算法完全相同，全部位於
② Promising（③ Sourcing 已於 2026-07-29 移出範圍）。

**但深做與否會影響本系統的演算法總量**：裝箱（cartonization）是系統實際會算的第二個
決策，屬履約層而非最小版。③ 移出之後，深做與否決定了整個系統有一個還是兩個演算法。

## F3：出貨單完整生命週期

最小版是兩態，揀貨確認即出貨。深做版補上中間狀態：

```text
最小   CREATED ─────────────────────────────▶ DEPARTED

深做   CREATED ──▶ PICKING ──▶ PICKED ──▶ PACKED ──▶ DEPARTED
```

| 動作 | 內容 |
| --- | --- |
| `ShipmentStatus` 加三態 | `PICKING`、`PICKED`、`PACKED` |
| 加 `shipment_lines` 表 | `(shipmentId, orderLineId, requestedQty, pickedQty)`。`requestedQty` 是**這個 shipment 要出的量**，拆單時不等於該 line 的總量 |
| 轉移守衛 | 每一態的前置條件 |

**這是狀態新增而非結構搬遷**：`ShipmentDeparted` 事件、`PickTask.shipmentId` 與
`PickTask.orderLineId` 在最小版就已是最終形態，跨層契約不變，DOM 端零改動。
加入 `shipment_lines` 後 `PickTask` 也不需要改掛——它直接指向 `orderLineId`。

若日後加入集貨區作業，`STAGED` 插入 `PACKED` 與 `DEPARTED` 之間，風格一致。

### 這一項解鎖了什麼

「已揀未出」的窗口重新存在——貨已離開儲位但尚未離倉。這是 F4 與 F5 的前置。

## F4：複核裝箱

| 動作 | 內容 |
| --- | --- |
| 加 `packages` 表 | `shipmentId`、`packageNo`、`weightGram`、內容明細 |
| `PackShipmentUsecase` | `PICKED` → `PACKED` |

`Package` 作為 `Shipment` 的內部實體，不獨立成聚合——它沒有獨立的生命週期，永遠隨
出貨單存續。

**前提**：3D bin packing（裝箱最佳化）需要 SKU 材積。`skus` 主檔存在，但 `volume_cm3`
在精簡時被排除——它需要「重量 vs 材積重取大者」的邏輯，多一層但決策類型不變。因此
F4 只做箱單記錄，不做裝箱演算法。若要做，須先在 `skus` 補回材積欄位。

## F5：回架 putback

**最小版最實質的損失，也是深做版最值得的一項。**

已揀未出時取消，貨必須放回儲位才能重新可用：

| 步驟 | 動作 |
| --- | --- |
| 1 | `Shipment` → `CANCELLED` |
| 2 | 已 `PICKED` 的 `PickTask` 逐一回架 |
| 3 | 回架完成後，發事實給庫存側取消那段出庫搬運（`AllocationReservationCanceller`） |

**順序不可顛倒。** 若先取消搬運、把量還給庫存，會出現「ATP 顯示可用、但貨還在出貨區」的
視窗，此時新訂單可能配到不存在的可揀庫存。

判斷「揀到哪了」讀 `PickTask` 狀態，`StockQuant` 不持有此資訊——同一事實只記一處。

### 為何值得

回架是「**物理動作的補償是另一個物理動作，不是資料回滾**」的最好例子，比短揀更能
說明履約層與 DOM 的本質差異：

| | DOM | 履約層 |
| --- | --- | --- |
| 取消配貨 | `release()` —— 一次資料操作 | 回架 —— 一個真實的搬運動作 |
| 失敗時 | 交易回滾即可 | 貨已經被搬走了，回滾不會把它搬回來 |

連帶：交會點 4 從最小版的兩種補償路徑恢復為四種。

## F6：收貨上架（由目前一段式收貨演進）

最小版的儲位庫存由 seed 直接建立。深做版補上上架這一段：

```text
GoodsReceived ──▶ 選儲位規則 ──▶ 入庫搬運的目的地改為該儲位
```

目前本系統擁有簡化的一段式收貨：確認時直接建立並完成 inbound picking／move／move line。
深做版再把它拆成到貨、驗收、上架等 checkpoint；每個 Kafka handler 或 Temporal Activity 都應
呼叫同一組 transactional use case，且只能在完成可入庫的 movement 時增加 `StockQuant`。

| 動作 | 內容 |
| --- | --- |
| 外部 WMS 的 putaway 能力 | 收貨上架 |
| 選儲位規則 | 同貨主同 SKU 已有儲位則併入，否則取同 zone 的空儲位 |

規則型，不做 ABC 分類或動線最佳化。

### 這一項與可用庫存探針的對應

dev 探針扮演外部 WMS producer，直接送一則「庫存已可配」事件。正式環境應由真實收貨上架流程
在 checkpoint 完成後發布同一契約。外部 WMS 保存實體 movement ledger，本系統保存 Promising
projection；兩者必須靠事件冪等與對帳機制維持一致，而不是假裝共用同一本執行帳。

## F7：盤點任務

| 動作 | 內容 |
| --- | --- |
| 加 `cycle_count_tasks` 表 | 帳差發生時產生 |
| 觸發點 | 短揀處理鏈的第 7 步 |
| `Location.status` | 盤點期間標記 `BLOCKED`，需 F1 |

最小版的短揀只修正帳，不產生後續任務。F7 補上「帳差需人工確認」這一環，讓短揀處理
鏈完整。

---

## 升級路徑驗證

逐項確認為何不需搬遷既有結構：

| 增量 | 動到既有的什麼 | 為何不痛 |
| --- | --- | --- |
| F1 | `locations` 加欄位 | 資料由 `code` 拆解而來，既有列不需重建 |
| F2 | `pick_tasks` 加 `sequence` | 新欄位，既有查詢不受影響 |
| F3 | `ShipmentStatus` 加三態 | enum 新增值；`ShipmentDeparted` 契約不變 |
| F4 | 新表 | 不動既有 |
| F5 | 新流程 | 讀既有的 `PickTask` 狀態，不改它 |
| F6 | 新流程 | 改入庫搬運的**目的地**，不改結構 |
| F7 | 新表 | 不動既有 |

**跨層契約完全不變。** `OrderAllocated`、`ShipmentDeparted`、`ShortPickDetected`、
`ShipmentCancelled` 四個事件的形態在最小版即為最終形態，因此 `order-promising`
module 在整個升級過程中零改動。

---

## 對 Demo 操作台的增量

最小版一頁，深做版三頁：

| 頁 | 最小版 | 深做版 |
| --- | --- | --- |
| 揀貨頁 | ✓（含儲位庫存與對帳差異） | ✓ 加動線順序顯示 |
| 出貨單頁 | ✗ | **新增**：出貨單狀態與明細、裝箱、出庫按鈕、**取消觸發回架** |
| 儲位頁 | 併入揀貨頁 | **獨立**：儲位階層瀏覽、盤點任務 |

「取消觸發回架」是深做版操作台最有價值的新控制項——它讓觀看者看見「補償一個物理
動作需要另一個物理動作」。

---

## 何時該升級

以下任一成立時，最小版的說服力開始不足：

| 信號 | 對應增量 |
| --- | --- |
| 需要展示「物理動作的補償」而不只是「帳的修正」 | F3 + F5 |
| ①C 多品項完成，一張 Order 對多個 Shipment | F3 |
| 需要展示入庫到出庫的完整循環 | F6 |
| 儲位數量增加到動線順序看得出差別 | F1 + F2 |

若上述皆不成立，最小版已涵蓋履約層唯一不與 DOM 同構的問題類型（兩本帳不一致），
升級不會增加新的能力類別。

---

## 明確不做

以下項目在深做版**仍然不做**，與最小版一致：

| 項目 | 理由 |
| --- | --- |
| 3D bin packing | 需要 SKU 材積，而 `volume_cm3` 已在精簡時排除 |
| 揀貨路徑 TSP | 以儲位編碼排序替代（F2） |
| 波次 Wave（W12） | demo 規模看不出合併效益 |
| 庫內移動、併板（W13） | 需要揀貨位／儲存位分離，本系統儲位為單一階層 |
| 補貨策略（W14） | 同上 |
| 批號、效期、序號（W15） | 不影響揀貨與出庫的正確性 |
| WES／WCS、設備控制（W16） | 履約層止於指令 |
| YMS 月台調度（W17） | 同層獨立系統 |
| 人員績效（W18） | 與領域正確性無關 |
| 出貨後的配送追蹤 | 屬 TMS，系統終點為 `DEPARTED` |
| 退貨驗收、上架回池 | 屬 RMS，連帶要求 `DEPARTED` 後不允許取消 |
