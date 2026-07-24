# hot-sku-concurrency-demo Specification

## Purpose

TBD - created by archiving change 'add-hot-sku-concurrency-demo'. Update Purpose after archive.

## Requirements

### Requirement: Execute a hot-SKU concurrent submission burst

The allocation SIT suite SHALL provide a named scenario that creates 1,000 PENDING Orders for one SKU with ten units of on-hand inventory and submits one distinct `OrderPlacedIntegrationEvent` for each Order through the allocation Kafka entrypoint. The scenario SHALL release all submissions from one start gate while the existing datasource pool bounds simultaneous database transactions.

#### Scenario: One thousand orders compete for ten units

- **GIVEN** one StockPool for `HOT-SKU` with on-hand quantity ten and 1,000 PENDING Orders for `HOT-SKU`, each with quantity one
- **WHEN** 1,000 distinct OrderPlaced events are submitted concurrently through the allocation entrypoint
- **THEN** the scenario processes every submitted event without changing allocation policy or event contracts


<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->

---
### Requirement: Demonstrate a real optimistic-lock conflict

The hot-SKU scenario SHALL synchronize the first two allocation attempts after they load the same StockPool and SHALL verify that persistence observes at least one real optimistic-lock conflict. The scenario SHALL exercise the existing allocation retry policy rather than injecting a synthetic conflict exception.

#### Scenario: First allocation wave writes stale StockPool state

- **GIVEN** the first two allocation attempts have loaded the same StockPool version
- **WHEN** the test-only synchronization gate releases both attempts
- **THEN** one stale write is rejected by JPA optimistic locking and allocation continues through the configured retry flow


<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->

---
### Requirement: Redeliver retry-exhausted events safely

The hot-SKU scenario SHALL collect only `AllocationConcurrencyExhaustedException` failures from the concurrent wave and SHALL redeliver each original event with the same event ID after the wave completes. The scenario SHALL fail if retry-exhausted deliveries remain after its bounded recovery limit or if any other delivery failure occurs.

#### Scenario: Exhausted event converges on redelivery

- **GIVEN** an OrderPlaced event exhausted the application retry policy and its transaction rolled back
- **WHEN** the scenario redelivers the same event ID after the concurrent wave
- **THEN** Inbox accepts the event as new and the Order reaches one final allocation outcome


<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->

---
### Requirement: Reconcile final allocation state

After all deliveries and bounded redeliveries complete, the hot-SKU scenario SHALL assert the persisted allocation invariants: ten allocated Orders, 990 backordered Orders, ten ACTIVE StockReservations with total quantity ten, and a StockPool with on-hand quantity ten, reserved quantity ten, and available-to-promise zero. Each of the 1,000 event IDs SHALL have an Inbox claim, and allocation outcome Outbox records SHALL total 1,000 and correspond to the final Order outcomes.

#### Scenario: Final state contains no oversell or lost event

- **GIVEN** all 1,000 submitted event IDs have completed processing
- **WHEN** the scenario queries StockPool, Orders, StockReservations, Inbox, and Outbox persistence
- **THEN** all reconciliation invariants hold and no Order has more than one ACTIVE StockReservation

<!-- @trace
source: add-hot-sku-concurrency-demo
updated: 2026-07-24
code:
  - docs/stock-reservation-design.md
  - docs/done/demo-01-hot-sku-concurrency-implementation.md
  - gradle/libs.versions.toml
tests:
  - order-promising/src/sit/java/com/flowzati/archone/allocation/entrypoint/kafka/AllocationHotSkuConcurrencyIntegrationTest.java
-->