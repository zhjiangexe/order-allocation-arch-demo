## Why

R3（`add-batch-stock-and-fefo`）把 `archone.allocation.partition-key-strategy` 的值由 `sku`
改名為 `stock`——key 的組成不再含 SKU，名字若留著就變成謊言。**但那次改名只改了 R3 的 delta
直接改寫的東西**，三個既有 requirement 與前端一處比對沒跟上。

前端那一處**不是用詞問題，是一個真的缺陷**：

```text
AppHeader.tsx    partitionKeyStrategy === 'sku'   ← 後端已改送 'stock'
                 → 比對永遠失敗
                 → header 把 v3 single-writer 標示成「v1 樂觀鎖」
```

比對失敗不會拋錯、不會留 log，只會安靜地說錯。而「現在跑的是哪個策略」正是這個操作台要展示
的東西——說錯等於整個 demo 的結論是錯的。之所以漏掉：`AppHeader` 完全沒有測試檔。

三個既有 requirement 則是 R3 的 delta **應該包含卻沒有包含**的 MODIFIED。archive 之後它們留在
`openspec/specs/` 裡，以權威的姿態說著一個已經不存在的設定值。

## What Changes

- **前端的比對改用具名常數**，字面與後端的 `STOCK_STRATEGY` 一致，並補 `AppHeader.test.tsx`
  釘住兩個分支。**無法辨識的值一律當非 single-writer**：往保守方向倒——說成 v1 只是少報一個
  能力，說成 v3 則是宣稱一個並不成立的保證。
- **三個既有 requirement 的策略名更新**：`outbox-event-delivery` 的配置結果事件、
  `demo-only-probes` 的設定端點、`demo-console-frontend` 的 header 對照表。
- **`outbox-event-delivery` 一併補上一個 R3 之後才存在的例外**：`AllocationDomainEventTranslator`
  現在發兩類事件，配置結果事件一律以 `orderId` 為 key，而**續做喚醒事件一律以爭用群組為 key**。
  原本的 requirement 以 topic 界定範圍，字面上沒有涵蓋後者，但標題「Allocation outcome events
  key by order identity」很容易被讀成「那個 translator 發的都以 orderId 為 key」。
- **`AllocationDomainEventTranslator` 的 Javadoc** 還寫著「使 consumer 成為該 SKU 的 single
  writer」，同一次改名的殘留。

**不改任何行為，除了 header 的標示。** 設定值、key 的組成、topic、payload 全部不動。

## Capabilities

### Modified Capabilities

- `outbox-event-delivery`——配置結果事件的 requirement 更新策略名，並明確劃出續做喚醒事件的例外。
- `demo-only-probes`——設定端點的 requirement 更新策略名。
- `demo-console-frontend`——header 的 requirement 更新策略名，並要求比對字面與後端一致、
  未知值往保守方向解讀。

## Impact

**前端**：`AppHeader.tsx` 一處比對 + 新增 `AppHeader.test.tsx`（4 支）。

**後端**：`AllocationDomainEventTranslator` 的 Javadoc 一段。無程式邏輯改動。

**沒有 migration、沒有合約改動、沒有設定改動。** 這個 change 的存在理由是讓
`openspec/specs/` 的每一句話都能追溯到某個 change——直接手改 live spec 也會通過 validate，
但 archive 目錄就不再能回答「這句話是誰決定的」。

**為什麼不併進下一個 change**：header 標錯策略會讓任何依賴操作台判讀的 demo 得出錯的結論，
而 R4 的範圍與分區策略無關，夾帶進去會讓那個 change 的 delta 說不出自己在做什麼。
