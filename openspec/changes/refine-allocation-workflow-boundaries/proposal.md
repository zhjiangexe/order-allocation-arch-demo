## Why

Initial order allocation and backorder waking already share the same stock decision, but their
application responsibilities are obscured by a movement assigner that also loads candidates,
persists execution state, and publishes outcomes. Movement recording likewise always creates a
picking for its current callers even though a move does not inherently require one, making it hard
to extend inbound, standalone-move, or durable workflow orchestration without copying policy or
splitting a required local transaction.

## What Changes

- Keep initial allocation behind one transactional application use case while separating demand
  intake, movement recording, allocation decision/application, and outcome recording into explicit
  reusable responsibilities.
- Reuse the same allocation decision and assignment semantics for initial attempts and bounded
  backorder wake rounds without making either flow call the other flow's use case.
- Separate continuation-message handling from the inbound availability flow while keeping the first
  wake round in the same local transaction as the stock increase that triggered it.
- Give each allocation transaction a transport-neutral command/result boundary. Kafka handlers
  continue to translate Integration Events into those commands; a future Temporal Activity can
  invoke the same transactional facade instead of calling its internal application components.
- Separate execution of one bounded wake round from the decision to schedule another round. The
  round returns an explicit result; the current Integration Event flow records a continuation from
  that result, while a future Temporal Workflow can use it as its loop condition.
- Make allocation outcome policy explicit: an unsuccessful initial attempt records one backorder
  fact; an unsuccessful wake attempt records no duplicate backorder fact; every successful attempt
  records allocation completion.
- Define `StockMove` as the required inventory-movement fact and `StockPicking` as an optional work
  grouping. Order-driven outbound movements continue to require one picking per order as their
  ship-complete grouping boundary.
- Keep object creation simple through existing constructors and named factory methods; do not add
  factory or creator classes solely to imitate DDD terminology.
- Preserve current Integration Event contracts, Inbox/Outbox behavior, FIFO/FEFO policy, persistence
  schema, and public HTTP APIs.
- Leave Temporal runtime integration out of scope. The resulting boundaries expose the commands and
  results a future Activity needs, but enabling Temporal still requires durable replay of Activity
  results because the current Inbox records only whether an event was processed.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `stock-allocation`: Formalize the shared initial/wake allocation semantics, their distinct failure
  event policies, transport-neutral command/result boundaries, and the transaction boundary around
  stock availability and the first wake round.
- `stock-movement`: Make picking optional for a generic move while retaining a mandatory per-order
  picking for order-driven outbound ship-complete execution.

## Impact

- Refactors the allocation application layer, especially `AllocateOrderUsecase`,
  `MovementAssigner`, replenishment/wake orchestration, and their unit tests.
- Makes the current Integration Event choreography and a future Temporal orchestration share the
  same coarse-grained transactional use cases without making the two runtimes coexist.
- Clarifies the contracts of `StockMove`, `StockPicking`, movement recording, and waiting-movement
  projection without changing the current database nullability of `stock_moves.picking_id`.
- Updates the living stock reservation design after implementation; archived OpenSpec changes and
  historical `docs/done` records remain unchanged.
- Adds no external dependency and no Temporal runtime, worker, task queue, or Workflow definition.
