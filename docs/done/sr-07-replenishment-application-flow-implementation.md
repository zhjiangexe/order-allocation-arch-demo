# SR-07 Replenishment Application Flow 實作紀錄

狀態：已完成

完成日期：2026-07-24

## 實作範圍

SR-07 完成收到補貨 Integration Event 後的補貨與 backorder 配置流程。

- `StockReplenishedIntegrationEvent` 在建立時拒絕零或負的補貨量。
- use case 以 Inbox 的 `eventId` 先取得處理權，重複訊息不再更新庫存或配置訂單。
- 依 SKU 讀取 StockPool，再以 repository 提供的穩定 FIFO 順序取得 BACKORDERED Orders。
- 預設 Strict FIFO policy 在第一張無法完整配置時停止，後續較小訂單不得跳過前單。
- 每張成功配置的 backorder 都建立一筆 ACTIVE `StockReservation`，避免 `reservedQuantity` 與 reservation 資料脫鉤。
- Coordinator 在同一 application transaction 路徑中保存 StockPool、成功配置的 Orders 與 Reservations，並發布各訂單的 `OrderAllocated` 與 `OrderAllocationCompleted` Domain Events。

## 變更檔案

### `../../order-promising/src/main/java/com/flowzati/archone/allocation/application/coordinator/OrderAllocationCoordinator.java`

- `replenishAndAllocateBackorders(...)` 在補貨並選出可配置的 Orders 後，為每張成功訂單建立 ACTIVE `StockReservation`。
- 新增批次保存路徑：先保存已變動的 StockPool，接著保存成功配置的 Orders 與對應 Reservations。
- 每一組 Order／Reservation 都發布一筆 `OrderAllocationCompleted`，使後續 SR-12 translator 可取得完整 Integration Event payload。
- 保持沒有 backorder 時仍會保存 StockPool，因為 `onHandQuantity` 已經變動。

### `../../order-promising/src/test/java/com/flowzati/archone/allocation/application/usecase/ReplenishmentUsecaseTest.java`

- 補充成功配置時驗證建立 reservation 的 order、stockPool、quantity、ACTIVE status 與 reserved time。
- 部分配置時確認只有 FIFO prefix 的成功訂單建立 reservation。
- 沒有 backorder、重複訊息與未知 SKU 時確認不會建立 reservation；未知 SKU 會在查詢 backorders 前失敗。

### `../stock-reservation-design.md`

- SR-07 checkbox 更新為完成。
- 明確補上 successful backorder 必須建立 ACTIVE reservation 的規則。
- 整體進度更新為 `11 / 17`。

## 驗證結果

執行完整 verification lifecycle：

```bash
./gradlew :order-promising:check
```

結果：`BUILD SUCCESSFUL`。

驗證包含：

- 補貨正向增量驗證。
- Inbox 重複訊息冪等。
- FIFO、head-of-line blocking 與部分配置。
- 無 backorder 時只保存已補貨的 StockPool。
- 未知 SKU 的錯誤處理。
- 每張成功配置 backorder 都建立並保存 ACTIVE reservation。
