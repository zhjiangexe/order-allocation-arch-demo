## Why

Inventory allocation behavior is now separated into candidate selection, pure planning and atomic commitment, but the source packages
still mix domain concepts, application workflow models, persistence ports, transport contracts and mutable aggregates. Leaving those
boundaries ambiguous would make the next wave, WMS and multi-policy extensions progressively harder to add without coupling them to
today's order-specific path.

## What Changes

- Adopt a pragmatic record-centric DDD model for `StockOperation`, `StockMove` and `StockMoveLine`: domain records retain their lifecycle
  rules, while application coordinators and committers own cross-record consistency and transaction boundaries. Keep stateful
  consistency owners such as `StockQuant` and `StockOperationCancellation` as true aggregates.
- Reorganize Inventory application code by feature, with each feature owning its command/result models, output ports and orchestration;
  stop using global `query`, `result`, `service`, `dto`, `enum` or `type` packages as miscellaneous containers.
- Move assignment workflow models such as `AssignmentQueueKey` and `StockOperationPredecessor` out of the allocation domain and place
  candidate, backlog and supply repositories under assignment-owned application ports.
- Replace the stock-location read repository's mutable `StockQuant` results with a dedicated immutable application projection mapped
  directly by its JDBC adapter. Preserve ordering, expired-stock visibility, calculated available quantity and the existing REST JSON
  contract.
- Introduce application publication ports for allocation assignment, stock-operation lifecycle and stock-availability facts; translate
  those application results, snapshots or publication models to versioned integration contracts only in infrastructure messaging
  adapters without reintroducing aggregate-raised Domain Events.
- Remove framework and transport dependencies from domain and application models, including Spring component annotations on pure
  domain services and Jackson annotations on application views; wire domain services and transport mappings at outer boundaries.
- Strengthen architecture tests so aggregate repositories, application projection/persistence ports, entrypoints, infrastructure
  adapters and cross-context orchestration cannot drift back into ambiguous packages.
- Remove or relocate production-only helpers and empty package remnants that no longer have a production caller after the boundary
  alignment.
- **BREAKING (internal Java API only)**: Inventory package names and imports will change. Database schema, persisted values, REST APIs,
  integration-event schemas, allocation decisions and lifecycle behavior will not change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `stock-allocation`: Require immutable read projections, feature-owned assignment and order-intake ports, and contract-independent
  assignment, lifecycle and stock-availability publication while preserving FEFO, SHIP_COMPLETE, FIFO precedence, locking, replay and
  stock visibility behavior.
- `stock-movement`: Clarify the pragmatic record-centric lifecycle boundary for stock operations, moves and move lines and distinguish
  it from true Inventory aggregate ownership.

## Impact

The change affects package ownership and imports across `inventory-context`, Inventory architecture tests, monolith composition wiring,
JDBC read adapters and Inventory messaging adapters. It requires coordinated source moves and test updates but no database migration,
data rewrite, endpoint change, message-schema change or new runtime dependency. The stock-location view remains one ordered database
query, and the allocation candidate, planning and commit sequence remains behaviorally unchanged.
