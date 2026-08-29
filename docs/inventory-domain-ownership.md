# Inventory domain ownership

Inventory 是一個 bounded context，不是五個 bounded contexts。內部用五個 domain module 分清楚
「為什麼要移動」、「如何規劃」、「承諾了什麼」、「現在有多少」與「在哪裡」。

## 五個 domain module

| Module | 擁有的事實 | 代表類型 | 不負責 |
| --- | --- | --- | --- |
| Movement | 搬運意圖、方向、Operation/Move 狀態、完成與取消 | `StockOperation`, `StockMove`, `StockOperationType` | 選批、預留明細、現有量 |
| Allocation | demand/supply 快照與非權威規劃結果 | `StockOperationDemand`, `StockAllocationSupply`, `StockAllocationProposal` | DB lock、扣量、寫 Move Line |
| Reservation | 已承諾的 Move-to-Quant 關係及其建立/釋放 | `StockMoveLine`, `StockMoveLineStore` | 決定 FEFO 規則、擁有 Stock Move |
| Position | 目前 materialized stock position 與收貨 | `StockQuant`, receipt/visibility | 訂單流程、搬運生命週期 |
| Location | 庫位身分與 usage | `StockLocation`, `LocationUsageType` | WMS 執行策略 |

## Source、Target 與過程

```text
貨主出貨訂單（source）
        │ Reservation Intake：轉成 source-neutral movement command
        ▼
StockOperation + StockMove（Movement：要從哪裡搬到哪裡）
        │
        ├─ Allocation Planning：Demand + Supply → Proposal（純計算、可丟棄）
        │
        ▼
StockMoveLine（Reservation：某 Move 已承諾哪些 StockQuant）
        │
        ├─ release：刪除明細並還原 reserved quantity
        └─ completion：消耗 Position，完成 Move/Operation
        ▼
目的庫位／出貨交接（target）
```

訂單只是發起 source；Movement 是跨流程的 canonical intent；Allocation Proposal 不是資料庫事實；
Reservation 才是承諾；Position 是目前數量。這五句是判斷新類別 ownership 的首要規則。

## Package layout

```text
inventory/
  movement/
    operation/{domain,application,infrastructure}
      application/port
    operationtype/{domain,application,infrastructure}
      application/port
    registration/application
    completion/{application,entrypoint}
    cancellation/{domain,application,entrypoint,infrastructure}
      application/port
    visibility/{application,entrypoint,infrastructure}
      application/{port,query}
  allocation/
    planning/{domain,application,infrastructure}
      application/port
  reservation/
    intake/{application,entrypoint,infrastructure}
    assignment/{domain,application,entrypoint,infrastructure}
      application/port
    release/application
  position/
    onhand/{domain,application,infrastructure}
      application/port
    receipt/{application,entrypoint,infrastructure}
      application/port
    visibility/{application,entrypoint,infrastructure}
      application/{port,query}
  location/{domain,application,entrypoint,infrastructure}
    application/{port,query}
  entrypoint/temporal
  infrastructure/observability
```

Context-level `entrypoint` 與 `infrastructure` 只容納真正跨 workflow 的 adapter。Business type
不得為了方便放進這兩個例外目錄。

## Data-access port vocabulary

Inventory 的自訂資料存取 port 統一使用 `*Store`，並放在所屬 capability 的中性
`application.port`。Command/query 仍是 use case 的分類，但不再決定 data port 的 package 或 suffix；
讀、鎖與寫的效果由 `find...`、`inspect...`、`lock...`、`save...` 等方法名稱直接表達。

| Store 類型 | 語意 | 規則 |
| --- | --- | --- |
| canonical-model Store | 同一模型的普通讀取、明確 lock 與 mutation | 同一 persistence lifecycle 不再拆成成對介面 |
| planning-input Store | Demand、predecessor、supply、backlog projection | 只回傳 immutable planning input，不併入 canonical-model Store |
| visibility Store | Operator／diagnostic read model | 回傳 immutable View／DTO／projection，保持用途隔離 |

- `StockMoveStore` 同時負責 ordinary loading、依全域順序鎖定與保存 canonical `StockMove`。
- `StockMoveLineStore` 依 Move 載入明細，並負責 assignment 整批建立與 release 整批刪除。
- `StockQuantStore` 同時提供完整身分讀取、global lock order 與 counter 寫入。
- `StockOperationAssignmentCandidateStore` 與 `StockAllocationSupplyStore` 是獨立的 immutable planning-input
  Store，不因都讀資料庫就併入 `StockOperationStore` 或 `StockQuantStore`。
- `StockLocationViewStore`、`StockOperationViewStore` 與 `StockQuantViewStore` 是 focused visibility Store。
- Domain 不宣告任何 persistence port；Application 載入 Domain object 後再呼叫其行為。
- `Repository` 只出現在 Infrastructure 的 Spring Data `Jpa*Repository`，保留 framework vocabulary。

## 未來擴充

Posting、Traceability、Inventory Control、Availability 目前只是 seam，不建立空介面或空 package。
等到出現「實際執行與原承諾不同」、serial/lot custody、調整盤點或獨立 ATP 政策時，再以已存在的
行為與資料生命週期決定是否成立新 module。
