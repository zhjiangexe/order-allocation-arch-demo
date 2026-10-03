# ADR-0003：Separate inventory intent, commitment detail and materialized balance

- Status: `Accepted — Current`
- Date: 2026-09-26
- Decision owners: `Open — assign before production ownership`
- Decision register: DEC-004
- Quality scenarios: QA-001、QA-002、QA-006、QA-009

## Context and forces

3PL Inventory 必須同時回答需求意圖、批次／quant 承諾、現有餘額與 warehouse execution 結果。若只以 Order line 或
單一 reservation row 表達，會混合上游 identity、allocation policy、FEFO detail、movement lifecycle 與 balance，
使 cancellation、reallocation、handover completion 與 concurrency 難以維持一致。

Planning 結果可能因 concurrent writer 立即過期，因此 proposal 不能被當成 committed fact。

## Considered options

1. **Order line 直接扣 StockQuant**：缺乏 source-neutral movement、commitment trace 與 reversible reservation，拒絕。
2. **保存獨立 ATP balance**：形成 on-hand、reserved 之外的第三份真相，增加 drift risk，拒絕。
3. **Generic allocation entity 擁有完整 lifecycle**：會與 movement intent、WMS work 與 balance 重疊，拒絕。
4. **StockOperation → StockMove → StockMoveLine → StockQuant**：分離 group／policy、SKU intent、quant commitment 與
   materialized balance，接受。

## Decision

- `StockOperation` 是同一 source unit、route 與 assignment policy 的一致性／排程 group。
- `StockMove` 表達單一 SKU movement intent。
- `StockMoveLine` 表達 Move 對具體 Quant／批次的 committed detail，不擁有獨立 lifecycle。
- `StockQuant` 保存 on-hand、reserved 與 optimistic version；ATP 即時計算為 `onHand - reserved`。
- Planning model 可丟棄；只有 transaction 內重新驗證並建立 move lines、更新 quant 與 lifecycle 才是 committed fact。
- Current assignment policy 是 `SHIP_COMPLETE`，完整 operation 全部成功或完全不配置。

## Consequences

### Positive

- 可以從需求追溯到 movement、批次承諾與物化餘額。
- Allocation planning 與 commit 分離，能處理 concurrent change 而不把 stale proposal 當事實。
- Cancellation 與 handover completion 有明確可逆性及 transaction boundary。

### Negative

- Aggregate working set、lock order、optimistic retry 與 persistence mapping 較複雜。
- `StockOperation.state` 是 moves 的物化摘要，必須在同一 transaction 保持一致。
- 新增 partial policy 不能只調 planner，還會影響 lifecycle、publication、WMS input 與 tests。

### Neutral / follow-up

- Inventory core 使用 source-neutral identity，不以 `orderId` 作為必要模型欄位。
- WMS Shipment／work state 不進入 Inventory aggregate。

## Validation evidence

- [Inventory 架構](../architecture/inventory.md)。
- Domain tests：state transition、quantity invariant、FEFO、replay equality。
- Integration tests：schema、rollback、concurrency、assignment 與 event wiring。

## Revisit triggers

- 引入 partial assignment、substitution、cross-operation optimization 或不同 source route policy。
- Consistency boundary 必須跨越多個 StockOperation。
- 實測 contention／latency 顯示現行 working set 與 lock strategy 無法達成已定 SLO。

## Related decisions

- ADR-0002：Transactional Outbox／Inbox
- ADR-0005：Outbound Fulfillment Operations
