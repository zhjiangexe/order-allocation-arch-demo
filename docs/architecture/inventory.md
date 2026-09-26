# Inventory current architecture

更新日期：2026-09-26

本文件記錄現行 Inventory 的權威模型與不能被 package refactor 改變的業務不變條件。實際類別、SQL 與 caller
以 `backend/inventory`、database migrations 與 automated tests 為準。

## Responsibility

Inventory 接收 source-neutral 的庫存需求，建立 movement intent，規劃並提交 reservation，維持 stock position，
並在 warehouse fact 到達後完成或取消 movement。

Inventory 不擁有 Order lifecycle、商品主檔、Shipment／Wave／Pick lifecycle，也不負責跨 context 流程進度。

## Current internal modules

| Module | 擁有的事實 |
| --- | --- |
| `movement` | `StockOperation`、`StockMove`、路線、來源身分與 lifecycle |
| `allocation` | planning models、assignment policy、`StockMoveLine` 與 allocation transaction |
| `balance` | `StockQuant` 的 on-hand、reserved 與 optimistic version |
| `location` | `StockLocation` identity、usage 與 warehouse relationship |
| `bootstrap` | Inventory runtime wiring 與跨 module observability |

`Reservation` 與 `Position` 是有用的概念分類，但現行 production package 分別使用 `allocation` 與 `balance`；文件
不宣稱尚未存在的 package migration。

## Truth model

```text
source allocation unit
  └─ StockOperation                 operation group / policy boundary
       └─ StockMove                 one SKU movement intent
            └─ StockMoveLine        committed move-to-quant detail
                 └─ StockQuant      materialized on-hand and reserved balance
```

| Model | 權威內容 | 刻意不包含 |
| --- | --- | --- |
| `StockOperation` | owner、方向、起訖位置、source identity、assignment policy 與 moves 的物化摘要狀態 | SKU 數量、WMS work state |
| `StockMove` | 一個 SKU 的需求量、起訖位置、source-line trace 與 movement state | 具體批次、Order domain model |
| `StockMoveLine` | move 從哪個 quant 承諾多少數量 | 獨立 lifecycle；釋放時刪除 active row |
| `StockQuant` | `(owner, location, SKU, in-date, expiry-date)` 下的 on-hand、reserved 與 version | movement history、持久化 ATP |

`StockOperation.state` 是底下 moves 的物化摘要，目的是支援排程與查詢，不是與 moves 平行演進的第二套生命週期。

## Registration and identity

- Source adapter 把來源 identity、allocation-unit key 與穩定 source-line identity 正規化成 Inventory command。
- 每個已接受的 source allocation unit 建立一個 `StockOperation`；每個 canonical source line 建立一個
  `StockMove`。
- 一個 request 若跨越多個 source location，必須在註冊前拆成不同 operation。
- Inventory core 使用 source-neutral identity；不得把 `orderId` 當作 movement 模型的必要欄位。
- `(sourceType, sourceId, allocationUnitKey)` 是 registration idempotency boundary：相同內容重送回傳既有 identity，
  相同 key 搭配不同 immutable 內容必須拒絕。
- 公開 Inventory boundary 使用 `stockOperationId`；不引入 `AllocationDemand` 或 picking-named compatibility identity。

## Allocation and reservation

- 現行 policy 只有 `SHIP_COMPLETE`：同一 `StockOperation` 的全部 confirmed moves 必須在一個 transaction 內完整
  滿足，否則不建立任何 `StockMoveLine`、不增加任何 reserved quantity，也不推進 operation state。
- Planning 的 demand、supply 與 proposal 是 immutable、可丟棄的 working models；proposal 不等於承諾。
- Commit transaction 重新驗證 working set、建立 move lines、增加 quant reservation，並把 moves／operation 推進
  到 `ASSIGNED`。
- `StockQuant.availableToPromise()` 是 `onHand - reserved`；過期庫存仍存在，但不得成為可配置 supply。
- 可用 supply 依 owner、location、SKU 篩選，再依效期與入庫順序執行 FEFO；不得跨 Owner 或跨 warehouse 消耗。
- Initial allocation 與 replenishment wake-up 使用相同 assignment semantics；喚醒只是重新競爭，不放寬規則。
- 所有會同時碰多列 quant 的 writer 必須遵守一致 lock order，並以 optimistic-lock retry 處理短暫競爭。

## Movement lifecycle

```text
CONFIRMED --complete assignment--> ASSIGNED --warehouse handover--> DONE
     |                                  |
     └-------------- cancel ------------┘  only while warehouse work is reversible
```

- `CONFIRMED` 代表 movement intent 已存在，可能仍在等待庫存。
- `ASSIGNED` 代表 move lines 與 quant reservation 已在同一 transaction 完成。
- Outbound handover 後，Inventory 依已承諾 move lines 扣減 on-hand／reserved，完成 moves 與 operation，再發布
  canonical completion fact。
- 尚未 assignment 的 operation 可本地取消。
- 已 assignment 的 operation 必須先取得 WMS execution 可逆的持久確認；無法確認時不得釋放 reservation 或改變
  movement state。
- 取消／完成／重送必須冪等。相同 correlation 搭配不同 business content 是衝突，不可靜默接受。

## Transaction and publication rules

- Assignment、cancellation 與 completion 都以完整 `StockOperation` working set 為一致性邊界。
- Quant、move line、move、operation 與相應 Outbox fact 必須在同一 database transaction 更新。
- Application／Domain 不依賴 versioned Integration Event contract。
- Business transition 產生完整 Application Event；`application.port.*Publisher` 由
  `infrastructure.messaging.*IntegrationEventAdapter` 實作並同步寫入 Outbox。
- Consumer 只把 inbound contract 正規化成 command；狀態改變與後續 publication 由 transactional use case
  完成。

## Verification anchors

以下證據比本文的類別清單更可靠：

- Domain unit tests：state transition、replay equality、quant quantity invariant、assignment policy。
- Inventory architecture tests：layer、package 與 dependency direction。
- Monolith integration tests：schema constraints、transaction rollback、FIFO／FEFO、concurrency、event wiring。
- Integration-contract tests：versioned payload 與 JSON compatibility。

若新增 allocation policy、source type 或 movement lifecycle state，必須先定義其 authoritative fact、transaction
boundary 與 executable tests，再更新本文件。
