## Why

Initial order allocation and backorder waking already share the same stock decision, but their
application responsibilities are obscured by a movement assigner that also loads candidates,
persists execution state, and publishes outcomes. Movement recording likewise always creates a
pickings for its current callers even though a move does not inherently require one, making it hard
to extend standalone-move or durable workflow orchestration without copying policy or
splitting a required local transaction.

## What Changes

- Keep initial allocation behind one transactional application use case while separating demand
  intake, movement recording, allocation decision/application, and outcome recording into explicit
  reusable responsibilities.
- Reuse the same allocation decision and assignment semantics for initial attempts and bounded
  backorder wake rounds without making either flow call the other flow's use case.
- Separate receipt confirmation from backorder allocation. A committed availability increase
  records an Outbox fact that triggers allocation promptly, while a scheduler scans the same
  waiting queue as reconciliation; both adapters invoke the same transactional wake use case.
- Treat `StockPool` as the stock context's physical inventory source of truth. A local receipt
  confirmation enters through a synchronous stock REST endpoint, creates and completes an inbound
  picking/movement with move lines, and only then changes `StockPool` before waking backorders.
- Give each allocation transaction a transport-neutral command boundary. Kafka handlers
  continue to translate Integration Events into those commands; a future Temporal Activity can
  invoke the same transactional facade instead of calling its internal application components.
- Separate execution of one bounded wake round from its triggers. The availability Integration
  Event provides a low-latency first attempt, while a scheduler owns eventual convergence. No
  continuation control event is emitted between rounds; a future Temporal Workflow may introduce
  durable result replay before using a round result as its loop condition.
- Make allocation outcome policy explicit: an unsuccessful initial attempt records one backorder
  fact; an unsuccessful wake attempt records no duplicate backorder fact; every successful attempt
  records allocation completion.
- Define `StockMove` as the required inventory-movement fact and `StockPicking` as an optional work
  grouping. Order-driven outbound movements continue to require one picking per order as their
  ship-complete grouping boundary.
- Keep object creation simple through existing constructors and named factory methods; do not add
  factory or creator classes solely to imitate DDD terminology.
- Replace the ambiguous and warehouse-specific `FulfillmentNode` / `nodeId` / `warehouseId`
  vocabulary with `Facility` / `facilityId`, while retaining `StockLocation` / `locationId` for
  physical inventory and movement endpoints.
- Preserve Inbox/Outbox behavior and FIFO/FEFO policy. Because the system is not deployed, update
  Integration Event payloads, public HTTP APIs, and unreleased migrations directly without a
  compatibility layer.
- Leave Temporal runtime integration out of scope. The resulting boundaries expose the commands,
  transactional facades, and wake-round result that future Activities can reuse. Any result used
  for Workflow branching must be introduced with durable replay because the current Inbox records
  only whether an event was processed.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `stock-allocation`: Formalize the shared initial/wake allocation semantics, their distinct failure
  event policies, transport-neutral command boundaries, and the event-plus-scheduler boundary
  between stock availability and backorder allocation.
- `stock-movement`: Make picking optional for a generic move, retain a mandatory per-order picking
  for order-driven outbound ship-complete execution, and keep external availability changes out of
  the local movement ledger.

## Impact

- Refactors the allocation application layer, especially `AllocateOrderUsecase`,
  `MovementAssigner`, availability-increase/wake orchestration, and their unit tests.
- Makes the current Integration Event choreography and a future Temporal orchestration share the
  same coarse-grained transactional use cases without making the two runtimes coexist.
- Clarifies the contracts of `StockMove`, `StockPicking`, movement recording, and waiting-movement
  projection without changing the current database nullability of `stock_moves.picking_id`.
- Renames the catalog root and identifiers to `Facility` / `facilityId`, including pre-release REST,
  Integration Event, persistence, frontend, and test contracts.
- Replaces the dev-only Kafka stock probe with a synchronous stock-receipt REST command and restores
  inbound picking/movement completion as the auditable source of physical stock increases.
- Records a stock-availability fact in the receipt transaction, then runs backorder allocation in a
  separate transaction from both the Kafka trigger and a periodic reconciliation scheduler.
- Updates the living stock reservation design after implementation; archived OpenSpec changes and
  historical `docs/done` records remain unchanged.
- Adds no external dependency and no Temporal runtime, worker, task queue, or Workflow definition.
