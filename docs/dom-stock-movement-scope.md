# 庫存異動模型：source、route、operation 與 execution

狀態：現行設計（2026-08-27）

## 核心判斷

庫存不是一個可以任意加減的數字。每次變動都必須由有起點與終點的 `StockMove` 解釋：

```text
入庫      Vendors              -> WH/Stock
出庫      WH/Stock             -> Customers
調整      Inventory adjustment -> WH/Stock
```

`StockQuant` 是某位置、SKU、批次的物化餘額；movement 是改變餘額的原因。兩者都是真相，但回答不同問題。

## 四層模型

```text
Source                 Operation group       Quantity intent       Batch detail / balance
Order / Transfer  -->  StockOperation     -->  StockMove[]      -->  StockMoveLine[] <-> StockQuant[]
                                                |
                                                +-----------------> WMS Shipment / Wave / Task
```

| 層 | 回答 | 不回答 |
| --- | --- | --- |
| Source | 誰要求這次 operation？ | 庫存從哪個位置移出 |
| StockOperation | 哪些 moves 是同一份工作、採何 policy？ | SKU、quantity、WMS task |
| StockMove | 哪個 SKU 從哪裡到哪裡、需要多少？ | 實際使用哪批 |
| StockMoveLine | 這個 move 使用哪個 quant、多少？ | 獨立 lifecycle |
| StockQuant | 現在 on-hand/reserved/ATP 多少？ | 業務需求與 warehouse work |
| WMS | shipment/wave/task 如何執行？ | Inventory precedence 與 reservation ownership |

這不是四張表硬湊成一個 aggregate。`StockOperation` 與 `StockMove` 都有 repository；application transaction
在跨列操作時先鎖 operation，再鎖 ordered moves，最後鎖 globally ordered quants。

## 四種容易混淆的 source/target

1. **source document**：例如 ORDER + orderId，表示誰要求配貨。
2. **source line**：例如 orderLineId，表示 move 對應哪條上游明細。
3. **from/source location**：貨物路徑起點，例如 WH/Stock。
4. **to/target location**：貨物路徑終點，例如 Customers。

Source document identity 保存在 outbound operation；route endpoints 同時保存在 operation snapshot 與每個 move。
Inbound 沒有 source document，卻仍有 supplier -> internal location 的完整 route。

## 為何 operation 沒有 SKU 與 quantity

Operation 是 operation group，不是數量單頭。多 SKU ship-complete order 只有一個 operation，但每個 source line
各有 move。SKU 與 required quantity 若也放在 operation，就會出現必須同步的第二份數量真相。

Operation state 也是 moves 的 transactionally maintained summary；目前 `SHIP_COMPLETE` 要求 group homogeneous：

```text
CONFIRMED operation <=> all moves CONFIRMED, no move lines
ASSIGNED  operation <=> all moves ASSIGNED, exact move-line coverage
DONE      operation <=> all moves DONE, retained move-line evidence
CANCELLED operation <=> all moves CANCELLED, no move lines
```

## 為何 move line 就是 reservation detail

Assigned move line 已完整表達：

```text
moveId + stockQuantId + quantity
```

再建 `Reservation`、`AllocationSlice` 或自己的 status 只會複製同一事實。Lifecycle 由 parent move 決定：

- move `ASSIGNED` + line exists = current reservation；
- release = delete lines and return the same move to `CONFIRMED`；
- move `DONE` + retained line = completed execution evidence；
- confirmed/cancelled move 不得有 lines。

刪除 active line 會失去 release 前的 batch detail，因此 release/cancellation/completion transaction 同時寫入
immutable lifecycle Outbox snapshot；audit 在 event log，不在另一張 mutable reservation table。

## 與 Odoo 的相同與不同

概念對應：

| Odoo | 本系統 |
| --- | --- |
| `stock.picking.type` | `stock_operation_types` |
| `stock.picking` | `stock_operations` |
| `stock.move` | `stock_moves` |
| `stock.move.line` | `stock_move_lines` |
| `stock.quant` | `stock_pools`（Java 名稱 `StockQuant`） |

相同點是 move-centric：move 表達 quantity intent，move line 表達 detailed execution，quant 表達 balance。

不同點是本系統目前只有 MTS + hard `SHIP_COMPLETE`：Odoo 容許 partial availability、backorder、move chaining
與更廣泛的 standalone moves；本系統用 pure planner、exact coverage 與 homogeneous states 把整單原子性變成
硬 invariant。未來若加入 partial/backorder，不應復活 demand/slice ledger，而應擴充 operation summary policy 與
move lifecycle/relations。

## Plan、execution、audit 與 balance 的真相邊界

| 模型 | 現在負責的真相 |
| --- | --- |
| `StockAllocationProposal` | 純規劃結果；沒有 durable identity，不是承諾或 ledger |
| `StockMoveLine` | 目前精確 reservation；完成後是本次 move 使用 quant 的 retained evidence |
| WMS `Shipment/Wave/WarehouseWork/PickTask` | 倉內實際執行、短揀與操作員 checkpoint |
| lifecycle Outbox event | release/cancel/complete 前的 immutable audit snapshot |
| `StockQuant` | 目前 on-hand/reserved/ATP 的 materialized balance，不是 immutable transaction ledger |

目前 WMS 只回報整個 operation 的 safe handover/completion，因此 `StockMoveLine` 不表達 planned-versus-actual
批次差異，也沒有 correction/reversal 語意。若未來需要 LOT-A 10 的 reservation 實際以 LOT-A 8 + LOT-B 2
完成，extension seam 才是 `StockReservation/StockAllocation -> execution confirmation -> StockPosting -> StockQuant`；
它不是現行模型，也不應預先建立空殼類別。

## Inbound 與 outbound

- Inbound registration 建立 source-agnostic operation/move；completion 建立 retained move line、增加 quant on-hand，
  並發 availability fact。它不直接呼叫 outbound allocation。
- Outbound source registration 建立 confirmed operation/moves；assignment 只新增 move lines並改 state/counters，不
  替換 move identity。
- WMS handover 觸發 Inventory completion；Inventory 扣帳完成後再發 source-specific completion fact。

## 暫不做

- route/rule engine 與 move dependency graph；
- partial assignment / backorder split；
- lot/serial/package ownership；
- valuation/accounting（3PL 不擁有貨）；
- operator、wave、pick/pack/stage state 放進 Inventory；
- auto-repair reconciliation。

這些能力若出現，擴充點分別在 rule graph、assignment policy、batch identity、WMS 或 operation audit；不應把
source requirement、inventory movement 與 warehouse execution重新壓成同一個 aggregate。
