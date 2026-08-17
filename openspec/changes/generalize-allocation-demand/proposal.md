## Why

目前配貨等待佇列是由訂單需求與 `StockMove` 狀態間接推導而來，導致 allocation 模型假設所有出庫需求都來自 order。實際業務也可能有內部調撥、補貨、生產領料或人工出庫；需要一個不依賴 order 的通用需求模型，將「誰需要庫存」與「倉庫如何執行搬運」分開。

## What Changes

- 新增通用的 `AllocationDemand`，代表任何需要競爭可用庫存的需求，並以來源類型與來源識別碼連結 order、transfer、replenishment、production 或 manual request。
- **BREAKING** 將等待配貨的主要判斷從 order-specific `Demand` / `orderId` 與 `StockMove` 推導，改為持久化的 allocation demand 狀態；`PENDING` demand 才是配貨佇列的主要來源。
- 讓入庫與其他只增加 supply 的 movement 不建立 `AllocationDemand`；入庫完成後增加 `StockPool`，再觸發等待需求重新配貨。
- 以複合查詢找出可處理的候選需求：`AllocationDemand` 仍為 `PENDING`、存在尚未 `ASSIGNED` 的出庫 `StockMove`、相關 `StockPicking` 可執行，且 scope 有可用庫存。
- 將純配貨決策與執行結果套用分開：allocation service 產生 allocation plan，另一個 application component 將 plan 套用到 `StockPool`、`StockMove`、`StockMoveLine` 與 `StockPicking`。
- 將配貨完成結果抽象為通用 allocation completion fact；order 只是其中一種來源，下游再依來源類型更新各自 context。
- 保留 FIFO demand ordering、FEFO stock selection、ship-complete 與 optimistic-locking concurrency semantics。
- 移除配貨流程對 `picking.orderId IS NOT NULL` 作為通用需求判斷的依賴；order linkage 僅作為 order source 的識別資料。

## Capabilities

### New Capabilities

- `allocation-demand`: 定義通用配貨需求、來源類型、需求生命週期、需求行與等待配貨候選查詢。

### Modified Capabilities

- `stock-allocation`: 配貨佇列改以 `AllocationDemand` 為主，支援非訂單出庫來源，並維持 FIFO/FEFO、整單配貨與配貨完成事件語意。
- `stock-movement`: 明確區分 supply movement、demand movement 與 allocation demand；`StockMove` 和 `StockPicking` 作為執行狀態與作業分組，不再單獨代表通用配貨需求。

## Impact

- 影響 `order-promising` 的 allocation domain/application model、等待需求查詢、scheduler、availability event consumer、`MovementAssigner` 及其測試。
- 新增 allocation demand persistence、來源類型與狀態轉換，並調整 `StockMove` / `StockPicking` 的關聯與候選查詢。
- 需要調整 order allocation、internal transfer、replenishment、manual outbound 等需求來源的建立流程；入庫流程只負責確認 supply 與發布 availability fact。
- 需要更新 allocation completion domain/integration event payload，使事件攜帶通用 demand/source 識別資料；order-specific consumer 改為依來源類型處理。
- 需要新增 migration、repository projections、application use cases 與 domain/application tests；不新增外部依賴。
