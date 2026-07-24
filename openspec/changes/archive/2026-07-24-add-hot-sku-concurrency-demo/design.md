## Context

The allocation service already protects StockPool with JPA optimistic locking and retries an entire transactional use case up to two additional times. SR-18 proves that mechanism with two concurrent orders. Demo-01 expands the observable verification to 1,000 OrderPlaced deliveries for one SKU with ten units of on-hand inventory.

The existing SIT environment invokes the Kafka entrypoint directly while using PostgreSQL Testcontainers. This preserves command mapping, Inbox claims, transactions, persistence, and Outbox translation without requiring a Kafka broker for every allocation SIT.

## Goals / Non-Goals

**Goals:**

- Provide a repeatable, named SIT scenario that submits 1,000 distinct OrderPlaced events for one SKU.
- Force at least one real optimistic-lock conflict while allowing database connection concurrency to remain bounded.
- Model redelivery of retry-exhausted messages and verify that all 1,000 events reach a durable final allocation outcome.
- Reconcile the final persisted StockPool, Order, Reservation, Inbox, and Outbox state.

**Non-Goals:**

- Measure production throughput, latency, or database capacity.
- Start a Kafka broker, Debezium connector, or a separate load-testing tool.
- Change the three-attempt application retry policy, FIFO allocation policy, or Kafka error-handler configuration.
- Implement the FIFO replenishment or read-model replay demos.

## Decisions

### Submit 1,000 virtual-thread deliveries with bounded database concurrency

The test SHALL create 1,000 orders and matching, unique OrderPlaced event IDs, then release 1,000 virtual-thread tasks from one start gate. The existing datasource connection pool bounds the number of simultaneous database transactions, preventing the test from attempting 1,000 database connections.

This represents 1,000 concurrent submissions rather than a claim that 1,000 database transactions can run simultaneously. A fixed worker pool would be simpler but would hide the submission burst that the demo is intended to show.

### Force a real first-wave optimistic-lock conflict without a 1,000-party database barrier

A test-only interceptor SHALL pause the first two allocation attempts after their transactional use cases have loaded the same StockPool. Releasing those attempts together causes a real version conflict at persistence time. The other submissions proceed normally through the same entrypoint.

Waiting for all 1,000 tasks after aggregate loading would deadlock behind the datasource connection pool. Synthetic exception injection would test retry mechanics but would not demonstrate a real JPA optimistic-lock conflict.

### Replay only retry-exhausted deliveries

Each submission SHALL be invoked through the existing Allocation Kafka entrypoint. A delivery that throws `AllocationConcurrencyExhaustedException` SHALL be collected and redelivered using the same event ID after the concurrent wave completes, until no retry-exhausted deliveries remain or a bounded recovery limit is reached.

This mirrors at-least-once broker redelivery: the Inbox claim rolls back with the failed transaction, so the same event remains eligible for a later attempt. The test does not emulate a Kafka broker's backoff or DLT policy; it proves that reprocessing the original message converges safely.

### Reconcile persisted business invariants instead of only counting successful calls

The test SHALL assert all of the following after recovery:

- exactly ten Orders are `ALLOCATED` and 990 are `BACKORDERED`;
- exactly ten ACTIVE StockReservations exist, their total quantity is ten, and each references a distinct allocated Order;
- StockPool on-hand quantity is ten, reserved quantity is ten, and available-to-promise is zero;
- every one of the 1,000 event IDs is claimed in Inbox after its final successful transaction;
- allocation outcome Outbox records total 1,000 and correspond to the final Order outcomes.

These assertions detect overselling, lost messages, duplicate reservations, and divergence between aggregate state and published integration outcomes. Counting method completions alone cannot detect those failures.

## Implementation Contract

**Behavior:** Running the dedicated allocation SIT executes the named hot-SKU scenario and completes only when all 1,000 submitted events have reached `ALLOCATED` or `BACKORDERED` after bounded redelivery of retry-exhausted events.

**Interface / data shape:** The scenario uses existing `OrderPlacedIntegrationEvent` records with unique `eventId` and `orderId`, delivered through `AllocationKafkaIntegrationEventConsumer`. It does not add public commands, events, topics, or persistence tables. Test-only coordination may intercept the existing allocation coordinator to synchronize the first conflict wave.

**Failure modes:** A non-retry-exhaustion failure fails the scenario immediately. If retry-exhausted deliveries remain after the bounded recovery limit, the scenario fails and reports their event IDs/count. A mismatch in any reconciliation invariant fails the scenario with the observed state.

**Acceptance criteria:** The scenario runs against PostgreSQL Testcontainers, observes at least one real optimistic-lock conflict, completes the final outcome counts and persistence reconciliations above, and leaves no failed delivery unreplayed within the configured recovery bound. It can be run with the existing `:order-promising:sit` task and a test filter for the scenario class.

**Scope boundaries:** This contract covers only the hot-SKU allocation demonstration. It intentionally excludes broker transport, connector behavior, benchmark thresholds, FIFO replenishment, and read-model replay.

## Risks / Trade-offs

- [1,000 submissions increase SIT duration and database contention] → Use virtual threads, the existing bounded connection pool, a test timeout, and a bounded redelivery limit.
- [Natural contention may not always expose a conflict] → Synchronize the first two loaded attempts with a test-only interceptor so one real persistence conflict is deterministic.
- [Application retry can exhaust under contention] → Collect only `AllocationConcurrencyExhaustedException` and redeliver the original event ID after the concurrent wave.
- [Large assertion output can be difficult to diagnose] → Include final status counts, reservation totals, and unrecovered event IDs in failure messages.

