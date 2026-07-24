## Why

SR-18 validates a real optimistic-lock conflict between two orders sharing one StockPool, but it does not provide a repeatable hot-SKU demonstration at meaningful submission volume. The system needs an executable scenario that proves inventory, orders, and reservations reconcile without overselling under contention.

## What Changes

- Add the Demo-01 hot-SKU concurrency integration scenario: 1,000 orders compete for ten units of one SKU.
- Verify post-retry outcomes and reconcile StockPool, Order, and ACTIVE StockReservation invariants.
- Make the scenario repeatable using the existing PostgreSQL Testcontainers, transaction, Inbox/Outbox, and optimistic-lock retry flow.

## Non-Goals

- Do not change allocation policy, Kafka topics, or Integration Event contracts.
- Do not introduce a new load-testing framework or a production benchmark target.
- Do not implement the FIFO replenishment or read-model replay demos in this change.

## Capabilities

### New Capabilities

- `hot-sku-concurrency-demo`: Verifies inventory correctness and final reconciliation under optimistic-lock retry for a high-contention SKU.

### Modified Capabilities

- None.

## Impact

- Adds allocation SIT coverage and supporting test utilities without changing domain allocation rules, Kafka topics, or public Integration Event contracts.
- Uses the existing PostgreSQL Testcontainers and will take longer than ordinary SIT cases.
