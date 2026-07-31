## Why

收單至今只收一行。那是**政策而非結構**——schema 允許任意行數、`rehydrate` 不設限、測試一直
在造兩行的訂單走讀取路徑，就是為了讓放寬那天不必搬遷任何東西。

但放寬它**不是移除一個檢查**。採 ship-complete 之後，配貨的可滿足性判斷從「這個 SKU 夠不夠」
變成「整籃的每一個 SKU 同時夠不夠」，而現在的實作處處假設一次只碰一個 SKU：

```text
AllocationService.allocate(demand, List<StockPool>, now)   一組批 = 一個 SKU
requireBatchesMatchDemand(...)                             強制所有批同屬一個 (貨主,倉,SKU)
StrictFifoAllocationPolicy                                 remaining 是單一純量
AllocationRequest(sku, availableToPromise, decisionAt)     單一 SKU、單一額度
ReplenishmentUsecase                                       只查「補的那個 SKU」的批
AllocateOrderUsecase.requireSingleSku(demand)              明文踩在單 SKU 假設上
```

這些在單行下全部正確、沒有任何測試會失敗——那正是它們需要被一起改的理由。

## What Changes

**批次以 SKU 分組傳入配貨**：`allocate(Demand, Map<String, List<StockPool>>, Instant)`。
`requireBatchesMatchDemand` 的「恰好相等」保護完整保留——批的 SKU 集合必須等於需求的 SKU
集合，缺一個就拋錯，而不是靜默地只配得到的那幾行。

**餘量從純量變成映射**：`AllocationRequest` 改帶 `availableBySku`，policy 的 `remaining` 跟著
變。`break` 的判準從「這個 SKU 不足」變成「**任一** SKU 不足」——**仍是 `break` 不是
`continue`**，head-of-line blocking 是刻意保留的性質。

**取用計畫帶 shortfall**：`planPicks` 不再以空清單表示失敗，改回傳帶 `shortfallBySku` 的計畫。
多 SKU 之後「哪個 SKU 差幾件」是操作上必要的資訊，而現在它被丟掉了。

**補貨喚醒跨 SKU 取批**：現行查詢已是兩段（選單 → 取那些單的全部待配行），接上第三段——收集
所有涉及的 SKU，一次把批查回來。查詢次數固定為三次，不隨候選單數成長。

**收單放寬多行**，前端下單表單跟著支援多行。

## Capabilities

### Modified Capabilities

- `order-intake`——收單接受多行；佇列的 SKU 範圍仍是入口，但候選單可能跨 SKU。
- `stock-allocation`——整籃可滿足性、批次以 SKU 分組、取用計畫帶 shortfall。
- `demo-console-frontend`——下單表單支援多行。

## Impact

**不改 schema。** `order_lines` 在 R1 就允許任意行數，index 在 R4 已經調整過。這個 change
沒有 migration。

**不改 partition key。** 原 roadmap 寫著「per-SKU 的分區策略必須退場」，而 R3 已經把 key 改粗
成 `(貨主, 倉)`——一張單不管跨幾個 SKU 都只屬於一個 writer。**護欄**：不得把 SKU 加回 key，
`DomainEventTranslatorTest` 有一支測試斷言這件事。

**不必修 roadmap 任務 6 說的超賣。** 那條描述的是 `findBackordersBySkuInFifoOrder` 缺
`DISTINCT`、一張單兩行同 SKU 會被 join 成兩列而扣兩次庫存。**R4 移除了那支查詢**，取代它的
兩段式查詢在結構上排除了這件事：第一段 `SELECT DISTINCT order_id`，第二段按 `order_id` 摺成
一個 `Demand`，`demandFor()` 再把同 SKU 的多行加總。

**BREAKING（行為）**：多行訂單從被拒絕變成被接受，且它們的配貨是整籃原子的——一行配不到，
整張單都不預留。單行訂單的行為完全不變。

**已識別未排程**：head-of-line blocking 若成為吞吐瓶頸，正確的緩解是**給隊首的單保留額度**
（讓它佔住需要的量、後面的單用剩下的），不是把 `break` 改成 `continue`。後者放棄的是先來先
服務本身，而那是這個佇列存在的理由。
