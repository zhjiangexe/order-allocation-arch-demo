# Stock reservation 設計（歷史索引）

狀態：Superseded

這份文件原先描述 `stock_reservations`、order-centric FIFO 與後續的 demand/commitment/slice 模型。那些
runtime models 已由 move-centric stock operation 取代；保留本頁只為避免舊連結失效，不應用來實作新功能。

現行文件：

- [Move-centric allocation 與 precedence policy](architecture/allocation-precedence-policy.md)
- [Source → Operation → Move → Batch 程式閱讀指南](allocation-demand-flow.md)
- [Movement scope 與 Odoo 對照](dom-stock-movement-scope.md)
- [Cutover 與 reconciliation runbook](allocation-demand-boundary-and-cutover.md)

## 仍有效的原始原則

```text
availableToPromise = onHandQuantity - reservedQuantity
```

- `StockQuant` 是 location × SKU × batch 的 balance；
- reserved 不得為負，也不得超過 on-hand；
- ship-complete 不允許 partial reservation；
- 補貨後以嚴格 FIFO 重試；
- Inbox/Outbox 保障 at-least-once delivery 下的 transaction/idempotency；
- optimistic/pessimistic concurrency failure 不得被誤判為缺貨。

## 已被取代的模型

| 歷史模型 | 現行答案 |
| --- | --- |
| `stock_reservations` | assigned `stock_move_lines` |
| accepted demand table | confirmed `stock_operations + stock_moves` |
| allocation commitment/slices | assignment proposal（transient）+ move lines（durable） |
| order/demand ID 作 WMS key | `stockOperationId` |
| released reservation rows | lifecycle Outbox before-image；active lines 刪除 |
| demand backlog Store projection | confirmed operation shared-SKU queue |

歷史 migration 與 v1 event reader 仍會出現舊詞，是資料轉換與 replay compatibility，不代表 runtime 可以
重新依賴它們。任何新 writer、repository、health query 或 API 若再次建立 demand/slice lifecycle，必須先修改
OpenSpec 並說明為何 canonical moves 無法表達需求。
