# ADR-0004：Keep Events and Temporal drivers behind one fulfillment semantics

- Status: `Accepted — Current strategy`
- Date: 2026-09-26
- Decision owners: `Open — long-term driver choice is OPEN-001`
- Decision register: OPEN-001
- Quality scenarios: QA-003、QA-006、QA-007、QA-008

## Context and forces

Archone 已有 event-driven fulfillment chain，也需要評估 Temporal 對長時間等待、retry、visibility 與 recovery 的價值。
直接讓兩套 runtime 各自實作業務規則會形成兩個產品；立即只留其中一套，又會在缺乏 production evidence 前失去
比較與遷移能力。

Owner context 必須繼續擁有 Order、Inventory 與 Shipment terminal facts，不能把 workflow history 升格為第二套
business truth。

## Considered options

1. **只保留 Events**：最少 runtime dependency，但長時間協調與 operator visibility 需自行建設。
2. **立即由 Temporal 完全接管**：workflow visibility 強，但在 parity、cutover 與 operation evidence 未完成前風險高。
3. **永久雙寫／同時推進同一 Order**：會競爭 command、subscriber 與 terminal state，拒絕。
4. **Deployment-wide 互斥 driver，共用相同 use cases／facts**：保留評估與 migration 空間，接受為 Current strategy。

## Decision

同一 deployment 以設定選擇 `events` 或 `temporal`，所有 replicas 必須一致。兩個 driver 只能保存各自 coordination
checkpoint，並呼叫相同 Ordering、Inventory、WMS application boundary；canonical terminal fact 仍由 owner context
在自己的 transaction 內發布。

兩種 mode 必須通過相同 semantic parity scenarios。切換是 deployment-wide cutover：停止新 intake、盤點並
drain／migrate in-flight state、切換全部 replicas，再做 business reconciliation；不得以混合 mode rolling update
接手舊流程。

## Consequences

### Positive

- 可以用相同 business scenarios 比較 runtime，而不是比較兩套不同實作語意。
- Temporal 提供 workflow history／retry，Events 保留較直接的 event-chain baseline。
- Owner-context truth 與 business transaction 不受 orchestration technology 接管。

### Negative

- 需要維護兩套 coordination adapters、tests、deployment configuration 與 operator knowledge。
- 每次新增 fulfillment step 都必須同步驗證兩種 mode，增加 change cost。
- In-flight migration 若不能 drain，需要專門設計，不能由新 mode 自動猜測。

### Neutral / follow-up

- Temporal mode 仍依賴 Kafka／Outbox 接收 canonical facts。
- 長期保留哪個 driver 仍是 OPEN-001；本 ADR 只決定目前安全共存方式。

## Validation evidence

- [Fulfillment orchestration](../architecture/fulfillment-orchestration.md)的 parity matrix 與 cutover gate。
- Workflow unit tests、Events process-manager tests、`e2e/spec` dual-mode scenarios。
- `docker/README.md` 的 deployment-wide mode constraints。

## Revisit triggers

- 任一 driver 需要另一個無法等價表達的 business capability。
- 雙模式 change／operation cost 超過保留比較與 migration 能力的收益。
- Production failure recovery、latency、operator experience 與 ownership evidence足以選定長期 runtime。

## Related decisions

- ADR-0002：Transactional Outbox／Inbox
- ADR-0005：Outbound Fulfillment Operations
