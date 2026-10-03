# ADR-0002：Publish cross-context facts through transactional Outbox and Inbox

- Status: `Accepted — Current`
- Date: 2026-09-26
- Decision owners: `Open — assign before production ownership`
- Decision register: DEC-005
- Quality scenarios: QA-003、QA-004、QA-005、QA-010

## Context and forces

Archone 的 Ordering、Inventory、WMS 與 Fulfillment 需要交換已發生事實。Business state 與 event publication 若分開
提交，會產生 state committed 但 event lost，或 event delivered 但 state rollback 的 dual-write failure。Kafka、
consumer 與 network 也只能提供可能重送的 delivery，不能假設 handler 只執行一次。

同時，Application／Domain business code 不應依賴 Kafka、Debezium、JDBC 或 versioned Integration Event contract。

## Considered options

1. **Database commit 後直接 publish Kafka**：實作簡單但存在不可關閉的 dual-write gap，拒絕。
2. **Distributed transaction／XA**：增加 platform coupling 與 operational complexity，且不消除 consumer replay，拒絕。
3. **Transactional Outbox + CDC + transactional Inbox**：接受 at-least-once delivery，以 local transaction 與
   idempotency 保證 business effect。
4. **Synchronous cross-context call only**：可用於明確 application boundary 的 query／command，但不能取代 durable
   fact publication 與 recovery path。

## Decision

Business transition 在 owner context transaction 內同步產生 Application Event，經 application Publisher port 與
infrastructure Integration Event Adapter 寫入 Outbox。Debezium／Kafka Connect 從 WAL 將 record 傳到 Kafka。

Consumer 只把 versioned contract 正規化成 command；Inbox claim、use-case business effect 與 follow-up Outbox 在同一
transaction。`(subscriberId, messageId)` 是 delivery idempotency key；業務入口另以 stable business identity 保護
semantic duplicate。

## Consequences

### Positive

- Business state 與 outbound fact 原子提交，不存在 application dual-write window。
- Consumer restart、retry 與 DLT replay 可以安全重送相同 message identity。
- Domain／Application 不依賴 broker 或 integration contract。

### Negative

- 增加 Outbox／Inbox schema、WAL、connector、topic、retention、cleanup 與 reconciliation 的營運負擔。
- Event delivery 是 eventual；caller 不能把 HTTP／consumer acknowledgement 當成整個跨 context 流程完成。
- Connector identity、slot、offset 與 subscriber rename 變成持久 migration concern。

### Neutral / follow-up

- Exactly-once 指的是受 idempotency 保護的 business effect，不宣稱 transport exactly-once。
- 受控 DLT recovery 可以直接使用 producer，但必須保留 original identity 與 metadata。

## Validation evidence

- Inbox／Outbox persistence、transaction rollback、CDC 與 integration-event contract tests。
- [Messaging 維運](../operations/messaging.md)的 deployment、retention、DLT 與 incident procedures。
- Root `AGENTS.md` 的 Integration Event boundary rules。

## Revisit triggers

- Outbox／Inbox 無法滿足已量化的 latency、throughput 或 recovery objective。
- 採用不同 durable log／change-data-capture mechanism，但仍須保留相同 atomicity 與 idempotency properties。
- 某 interaction 需要同步 consistency；必須明確限定 boundary，不得默默繞過 publication policy。

## Related decisions

- ADR-0001：Modular monolith boundary
- ADR-0004：Dual orchestration drivers
