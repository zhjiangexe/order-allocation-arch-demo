# ADR-0005：Evolve Fulfillment into Outbound Fulfillment Operations

- Status: `Accepted — Target, not fully implemented`
- Date: 2026-09-26
- Decision owners: `Open — assign before implementation ownership`
- Decision register: DEC-001、DEC-002、DEC-003
- Quality scenarios: Traceability、operability、owner isolation；quantified SLA remains open

## Context and forces

Current Fulfillment capability 主要負責跨 Ordering、Inventory、WMS 的查詢、cancellation process 與 Temporal
coordination。3PL 的下一階段需求包含 intake review、Owner service policy、長時間 SLA、重大例外與人工確認。

這些語意超過單純 technical orchestrator，但尚未授權 Archone 做選倉、跨倉拆單、promise 或成本最佳化，也沒有
證據需要一個可任意切割 Order／Shipment 的 generic `FulfillmentUnit`。

## Considered options

1. **維持純 technical orchestrator**：無法擁有 intake、SLA 與人工 exception lifecycle，拒絕作為 Target。
2. **建立 generic Operations context 包含 inbound、outbound、returns**：語言與責任過寬，容易成為流程雜物箱，拒絕。
3. **升格 OMS／DOM**：需要 sourcing／promise 權責，目前不具備，拒絕。
4. **聚焦 Outbound Fulfillment Operations**：在現行 coordination 上增加 submission、case、milestone、SLA 與
   exception，同時保留 owner-context truth，接受。

## Decision

`backend/fulfillment` 的 Target 是 Outbound Fulfillment Operations bounded context：

- 待審期間只建立 `OrderSubmission` 與不可變 revision；不建立正式 Order、reservation 或 WMS work。
- 每張正式 Order 對應一個 `FulfillmentCase`，Case 組合 Order、StockOperation、Shipment 與 required milestones，
  但不覆寫來源 aggregate state。
- 正常 retry／local exception 留在 owner context；只有跨 context、需要人工決策或威脅 SLA 的問題才提升。
- 暫不建立 `FulfillmentUnit`；只有實際出現無法以 OrderLine、Shipment reference 與 milestone 表達的獨立履約分支
  才重開模型決策。
- Archone 仍不做自動選倉、商業拆單、promise 或 carrier optimization。

## Consequences

### Positive

- Domain name 與責任對齊 outbound 3PL operations，而不是模糊 generic orchestration。
- SLA、人工 review 與 exception 有可追蹤 ownership，不污染 Ordering／Inventory／WMS truth。
- 避免在沒有案例前建立 FulfillmentUnit 與過度通用 workflow abstraction。

### Negative

- Fulfillment 同時存在 Current coordination 與 Target operations 語意，文件與 code 必須清楚標示成熟度。
- Case projection 需要處理 eventual consistency、missing fact 與 reconciliation。
- Service Policy、Partner Integration／ACL、Control Tower 與 Billing 的 boundary 尚有 open decisions。

### Neutral / follow-up

- Inbound execution 目前留在 WMS／Inventory；Returns 先是獨立 capability。
- Control Tower 初期只讀 projection 與工作入口，不成為來源真相。

## Validation evidence

- [3PL 策略](../ddd/strategy.md)、[Context map](../ddd/context-map.md)與
  [Ubiquitous Language](../ddd/ubiquitous-language.md)。
- [Fulfillment orchestration](../architecture/fulfillment-orchestration.md)的 Current／Target separation。
- Target 尚未完整實作；在 code／tests 出現前不得將 submission、case、SLA 寫成 Current capability。

## Revisit triggers

- Archone 取得 sourcing、routing、promise 或 commercial split 的權責。
- 多 Shipment／多 fulfillment branch 無法由現有 references 與 milestones 清楚表達。
- Inbound、Returns、Control Tower 或 Billing 出現獨立 language、state transition 與 team ownership。

## Related decisions

- ADR-0001：Modular monolith boundary
- ADR-0003：Inventory commitment model
- ADR-0004：Dual orchestration drivers
