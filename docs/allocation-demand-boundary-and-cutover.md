# Move-centric stock operation boundary 與 cutover

狀態：現行設計。檔名保留既有連結。模型見
[Move-centric allocation policy](architecture/allocation-precedence-policy.md)，程式入口見
[Source → Operation → Move → Batch](allocation-demand-flow.md)。

## Boundary

- `stock_operations` 保存 source allocation unit、policy、queue/scheduling snapshot、route 與 group state。
- `stock_moves` 保存 source line、SKU、required quantity、route 與 quantity lifecycle。
- `stock_move_lines` 是唯一 current reservation / retained execution detail。
- `stock_pools.reserved_quantity` 是 assigned move-line sum 的 transactionally maintained counter。
- WMS Shipment 以 `stockOperationId` 冪等建立，但 Inventory 不擁有 wave/task/operator/packing state。
- Inbound operation 沒有 source document identity；它仍有 from/to location 與 moves。

不存在 runtime `AllocationDemand`、`Allocation`、`AllocationSlice` persistence。歷史 V12–V20 schema
已隨未上線系統的 migration 重整移除；Integration Event 的 retained-reader compatibility 不受本次 schema 整理影響。

## Canonical identity

| Identity | 唯一語意 |
| --- | --- |
| `sourceType + sourceId + allocationUnitKey` | 上游的一次 allocation unit；在 operations 唯一 |
| `stockOperationId` | Inventory operation group；WMS idempotency key |
| `sourceLineId` | 上游明細 trace；在同一 operation 唯一 |
| `moveId` | required stock movement identity，release/reassign 不更換 |
| `stockQuantId` | location/SKU/batch balance identity |
| `moveLineId` | move × quant 的 current reservation / execution detail |
| `shipmentId` | WMS 自己的 execution grouping identity |

## Historical expand / backfill / validate / contract

以下為 Git 歷史中的升級設計，當時將 V1–V20 視為固定 baseline。因系統尚未上線，
目前已合併為直接建立最終 schema 的 V1–V6，這些升級步驟不再執行。
現行初始化與既有開發資料庫處理方式見 [資料庫說明](../backend/deployments/monolith/src/main/resources/db/README.md)。

原 V21–V29 依序：

1. **Expand**：為 picking/move/WMS/cancellation operation 加入 nullable canonical identity。
2. **Backfill pickings**：每個 legacy demand 決定性對應一個 picking，複製 source、policy、route、queue facts。
3. **Backfill moves/downstream**：重用既有 move identity，補 source line/sequence 與 picking references。
4. **Validate**：檢查 cardinality、immutable content、state homogeneity、move-line coverage、quant counters 與
   downstream picking identity；任何 mismatch 立即停止 migration。
5. **Enforce**：建立 source-unit/source-line unique indexes 與 row-local checks。
6. **Cut downstream keys**：cancellation operation 與 WMS Shipment 改以 `stockOperationId`。
7. **Contract**：移除 demand/order-specific core columns 與 demand tables。
8. **Cross-row guard**：deferred constraint triggers 檢查 group、coverage 與 counter invariants。

原 V30 只做 metadata rename，保留既有 UUID、資料列、source identity、state、timestamps、versions
與 relationships，並在 canonical table/column names 上重建 deferred invariants。新版直接建立相同最終定義；
已有舊 Flyway history 的開發資料庫必須另行重建，不能將本次 baseline 當成原地升級。

## Contract rollout

1. 先部署同時接受 legacy v1/v2 與 canonical v3（Inventory lifecycle 為 v2）的 consumers。
2. producer 單版本切換：assignment 與 fulfillment facts 發 v3，Inventory lifecycle audit 發 v2。
3. 歷史 Outbox、topic retention 與 DLT replay window 內保留舊 reader；新 producer 不 dual-publish。
4. v1 event 沒有 canonical `stockOperationId`；reader 必須在 composition boundary 透過既有 `moveId` 查出唯一的
   `stockOperationId`。不可把 legacy `allocationId` 直接當成 `stockOperationId`，也不可為此恢復 demand/allocation persistence。
5. v1 reader 的刪除是之後獨立 change，不與資料模型 cutover 綁在一起。

## Cutover validation

必須證明：

- 每個 source allocation unit 恰有一個 canonical operation；
- 每個 source line 恰有一個 canonical move，immutable content 無 drift；
- operation 非空且與 moves state homogeneous；
- `ASSIGNED/DONE` move lines 精確覆蓋 demand quantity，`CONFIRMED/CANCELLED` 沒有 lines；
- quant reserved counter 等於 assigned move-line sum；
- WMS shipments 與 durable cancellation operations 都有 `stockOperationId`；
- final schema 沒有 demand/slice persistence 或 order-specific movement FK。

## Rollback limits

- 發出第一筆 canonical v3/v2 event 之前：可 pause writers、drain Inbox/Outbox、reconcile，再回退到仍能讀舊版事件的
  prior release。
- 發出第一筆 canonical v3/v2 event 之後：不得回退到只認 legacy contracts 的 binary；只能回退至同時接受 legacy 與
  canonical contracts 的 dual-reader compatibility release。
- V30 是 metadata rename，不以逆向 rename 作 operational rollback。migration 已套用後，prior binary 若仍查詢
  `stock_pickings`／`picking_id` 就不相容；需使用已驗證的 forward repair，或在災難復原情境 restore database backup。
- 不可同時啟用 legacy 與 canonical writers，也不可用 reconciliation 自動補帳掩蓋 drift。

## Deployment runbook

1. **Consumer-first**：先部署 dual readers，確認 Ordering、Inventory、WMS、bootstrap messaging 與 Temporal workflow
   同時接受 legacy 與 canonical payload；入口立即 normalize 為 `stockOperationId`。
2. **Quiesce and drain**：暫停會建立新 operation 的 writer，等待 Inbox/Outbox lag 歸零，執行 operation/move/quant
   reconciliation 並保存結果。
3. **Flyway-before-traffic**：在 application traffic 恢復前完成 V30，驗證 canonical tables、columns、foreign keys、
   indexes、functions 與 deferred triggers；任何驗證失敗都不開 traffic。
4. **Deploy canonical code**：部署只讀寫 `stock_operations`／`stock_operation_id` 的 application；先以 probes 驗證 source
   replay、allocation、release、completion、cancellation 與 WMS correlation。
5. **Producer single-version cutover**：新 assignment/fulfillment facts 只發 v3，Inventory lifecycle 只發 v2；同一 business
   fact 不 dual-publish。觀察 duplicate Shipment、duplicate completion、DLT 與 reconciliation 指標。
6. **Retain compatibility readers**：在 topic retention、Outbox re-snapshot、DLT replay、Temporal history retention 與最長
   workflow duration 全部越過部署門檻前，不移除 legacy readers。移除工作必須另開 cleanup change。

## Operations

- waiting/backlog：讀 confirmed operation + confirmed moves；
- reservation：讀 assigned move lines；
- execution evidence：讀 done move lines；
- ATP：`onHand - reserved`；
- health：`StockOperationReconciliationStore` + `stockOperation` HealthIndicator；
- lifecycle audit：`inventory.stock-operation-events` 的 `StockOperationLifecycleIntegrationEvent`。
