## Context

R1 把訂單的需求改成行的集合，但收單只收一行；schema、`rehydrate` 與測試 fixture 都刻意不設
限，好讓放寬那天不必搬遷結構。R3 把庫存分批、R4 把需求來源換成 `demand_lines` 並以訂單分組
查詢——兩者都預先做成了多行的形狀。

剩下的是配貨演算法本身：可滿足性判斷、餘量模型、補貨喚醒取批的範圍。

## Goals / Non-Goals

**Goals**

- 多行訂單被收單接受，且配貨對它們是整籃原子的
- 單行訂單的行為完全不變
- 「哪個 SKU 差幾件」成為可回答的問題

**Non-Goals**

- 不改 schema、不改 partition key、不改事件契約
- 不改 head-of-line blocking 的語意
- 不做部分配貨（`PARTIALLY_ALLOCATED` 在 ship-complete 下不存在）

## Decisions

### 批次以 SKU 分組傳入，不是攤平的清單

`allocate(Demand, Map<String, List<StockPool>>, Instant)`。

呼叫端本來就知道自己在查哪幾個 SKU 的批——分組是它已經有的資訊，攤平之後在配貨這一側再依
`skuCode` 分回來，等於把資訊丟掉再重建。

**真正的理由是可驗證性。** `requireBatchesMatchDemand` 現在的保護是「批的 SKU 集合**恰好等於**
需求的 SKU 集合」，寫成「包含」的話一張跨多 SKU 的訂單會通過檢查、然後只扣其中一個 SKU 的量
而整張單被標為已配。攤平的清單讓「某個 SKU 一批都沒有」與「有批但量不夠」長得一模一樣——
兩者都是「查不到」，而它們該導向不同的結果（前者是呼叫端給錯了批，後者是正常的缺貨）。

分組之後那條檢查是 `keySet().equals(demand.totalsBySku().keySet())`，缺一個 SKU 直接拋錯。

### 餘量是 per-SKU 的映射，而且 policy 拿不到「那個 SKU」

`AllocationRequest` 由 `(sku, availableToPromise, decisionAt)` 改為
`(availableBySku, decisionAt)`。

**刻意不保留「這次補的是哪個 SKU」。** 那是事件的屬性，不是決策的屬性；留著就會有人拿它寫出
「只檢查這個 SKU」的版本——而那正是這個 change 要消除的行為。policy 拿不到它，就寫不出來。

單 SKU 時映射只有一筆，行為與改動前完全相同。

### `break` 的判準是「任一 SKU 不足」，仍然不是 `continue`

```text
佇列：#1 要 A×10 + B×5，#2 要 A×3
補了 A×100，B 一件都沒有
  → #1 配不成（B 不足）→ break
  → #2 也不配，雖然 A 多到溢出來
```

FIFO 的內容就是這個：先來的沒拿到，後來的也不能插隊。差別只在於「拿不到」現在可能是因為
**別的** SKU——而那對排在後面的單來說沒有分別，它們仍然是後來的。

改成 `continue` 會讓 #1 在後面不斷有小單時永遠等下去。**若哪天吞吐成為問題，緩解是給隊首的
單保留額度**（佔住它需要的量，後面的用剩下的），先來先服務因此仍然成立——而不是放棄順序。
這一點寫在這裡，是因為 `break` → `continue` 是一個看起來只有一個字的改動。

### 取用計畫帶 shortfall，不以空清單表示失敗

```java
record AllocationPlan(List<BatchPick> picks, Map<String, Integer> shortfallBySku) {
  boolean isFeasible() { return shortfallBySku.isEmpty(); }
}
```

現在 `planPicks` 回空清單代表配不到，那是隱含約定；而多 SKU 之後**「哪個 SKU 差幾件」是操作
上必要的資訊**。R3 當時刻意把「為什麼配不到」推給庫存頁回答——`expired` 與
`availableToPromise` 兩個欄位合起來就分得出「過期了」與「被預留光了」。那個判斷在單 SKU 下
成立，多 SKU 之後不成立：使用者得逐一去查每個 SKU 才能拼出「這張單卡在哪」。

順帶讓整籃判斷變成一趟計算。現行是「先問可不可行、再算取哪些批」，有了 shortfall 就是同一次
遍歷的兩個輸出。

`AllocationOutcome` 不動——它回答的是訂單層級的「沒得配」與「不夠配」，而 shortfall 回答的是
「差在哪」。兩者不同層級，合併會讓 enum 變成無界的。

### 餘量與需求收成具名型別，不用裸 `Map<String, Integer>`

`availableBySku`、policy 的 `remaining`、`shortfallBySku` 三處都是「SKU → 數量」，而它們身上
有一組共同的不變式：數量非負、扣減不得為負、涵蓋檢查要對每一個 SKU 都成立。

裸 Map 會讓那些操作散在 policy 的迴圈裡，而這個 repo 已經有相反的做法——`StockContentionKey`
把 key 的組成收成型別、`requireSingleSku()` 把「容易寫錯的摺疊」收成具名方法。`covers()` 與
`minus()` 是同一條線。

### 補貨喚醒取批：選完單再一次查齊

現行查詢已是兩段（R4）：① 選出候選單、② 取那些單的全部待配行。接上第三段：③ 收集 ② 裡出現
的所有 `skuCode`，一次把批查回來。

**查詢次數固定為三次**，不隨候選單數成長。逐張單各自查會是 N+1，而一輪喚醒最多 200 張。

**更重要的是死鎖。** 本輪要碰哪些 `StockPool` 必須在進入交易前全部已知，否則
`OrderAllocationCoordinator` 的 `WRITE_ORDER` 全序無從先算。逐張邊查邊配的話，那個集合要到
配到一半才知道。R3 的喚醒上限讓候選單有界，這一段讓池的集合有界。

### 前端下單表單支援多行

操作台是這個專案展示決策的地方。「一張單要 A×10 與 B×5，B 只有 3 件 → 兩行都不預留、整張
缺貨」——這件事在 API 層看得到，但在畫面上才讓人相信，因為它反直覺（有貨卻不配）。

R3 的庫存頁、R4 的事件鏈都是同一個判斷。

## Implementation Contract

| 元件 | 動作 |
| --- | --- |
| `Order.place()` | 移除「恰好一行」限制，保留「至少一行」 |
| `AllocationRequest` | `(sku, availableToPromise, ...)` → `(availableBySku, ...)` |
| `SkuQuantities`（新） | SKU → 數量的具名型別，帶 `covers()`、`minus()`、`isEmpty()` |
| `AllocationPlan`（新） | `picks` ＋ `shortfallBySku` |
| `AllocationService.allocate` | 批次參數改為 `Map<String, List<StockPool>>` |
| `planPicks` | 回 `AllocationPlan`；逐行以 `line.skuCode()` 取對應的批 |
| `requireBatchesMatchDemand` | 檢查 `keySet()` 恰好相等 |
| `StrictFifoAllocationPolicy` | 餘量改映射，`break` 判準改「任一 SKU 不足」 |
| `MaximizeFulfilledOrdersPolicy` | 排序鍵改為整籃的某個度量（見 Open Questions） |
| `AllocateOrderUsecase` | 移除 `requireSingleSku`，改取多組批 |
| `ReplenishmentUsecase` | 第三段查詢：收集候選單的所有 SKU，一次取批 |
| `StockPoolRepository` | 新增「一次取多個 SKU 的可配批」的查詢 |
| 前端 | 下單表單的行編輯器（新增／移除行） |

## Risks / Trade-offs

**`MaximizeFulfilledOrdersPolicy` 的排序鍵沒有明顯的多 SKU 對應物。** 它現在依
`demandFor(skuCode)` 由小到大排——多 SKU 之後「小」是什麼？總件數？涉及的 SKU 數？這個 policy
目前沒有生產呼叫端（只有測試），所以不是阻塞問題，但要明確決定而不是隨便挑一個。見 Open
Questions。

**整籃檢查的短路順序會影響失敗訊息的確定性。** 先檢查最稀缺的 SKU 可以更快失敗，但那讓
「shortfall 裡有幾筆」隨檢查順序改變。決定是**不短路**：全部檢查完再回報，shortfall 因此是
完整的——那正是它存在的價值。

**多行訂單讓一次交易碰更多 `StockPool`。** 死鎖的防線是 `WRITE_ORDER` 的全序，而它從 R3 起
就寫成跨 SKU 的形式（`skuCode` 是第一個排序鍵），正是為了這一刻。

## Open Questions

**`MaximizeFulfilledOrdersPolicy` 的多 SKU 排序鍵。** 候選是「總件數最少」、「涉及 SKU 數最
少」、或「最稀缺 SKU 的需求量最小」。它沒有生產呼叫端，所以可以留到實作時依測試表達力決定；
但不能默默選一個而不寫理由。
