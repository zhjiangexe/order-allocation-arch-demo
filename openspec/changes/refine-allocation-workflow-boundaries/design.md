## Context

The initial allocation path currently claims an inbound message, reads untaken demand, records an
outbound picking and its moves, assigns stock, and records the outcome in one transaction.
Replenishment records and completes an inbound movement, then contains a second entry point that
wakes waiting outbound moves. Both paths reuse `MovementAssigner`, but that component also owns
outcome publication, while the initial use case alone publishes the failure outcome. The resulting
event policy is correct but distributed across classes.

The movement model already permits a null `picking_id`, matching the rule that a move is the
required inventory fact and a picking is only a work grouping. The current supported inbound and
outbound recorders nevertheless always create a picking. Order-driven outbound execution relies
on that picking as its ship-complete group, so making picking optional globally must not make it
optional for that flow.

The existing FIFO guarantee also constrains the split: stock becoming available and the first wake
round share one local transaction. Inserting an asynchronous boundary between them would allow a
new order to consume the new ATP before older waiting moves are considered.

The current command records are already independent of Kafka event classes, but the transactional
use cases return `void` and the Inbox remembers only that an event id was processed. That is enough
for event-consumer idempotency, but not enough to claim that a Temporal Activity can be enabled
unchanged: if the database commits and the Activity completion is lost, a retry must recover the
same result that the Workflow uses for branching.

## Goals / Non-Goals

**Goals:**

- Make the initial-attempt and wake-round outcome policies visible at their application-flow
  boundaries.
- Reuse one assignment implementation without making initial allocation and wake continuation call
  each other's use cases.
- Give continuation messages their own transactional use case while preserving direct invocation
  of the first wake round from the stock-availability transaction.
- Expose explicit allocation-attempt and wake-round results without importing Kafka or Temporal
  types into commands, results, or transactional use cases.
- Keep execution of one bounded wake round separate from the current mechanism that schedules its
  continuation.
- Keep movement creation simple and document precisely where picking is optional or required.
- Preserve all observable allocation, event, FIFO/FEFO, Inbox/Outbox, and persistence behavior.
- Leave a coarse-grained, idempotent application boundary that a future Temporal Activity could
  invoke.

**Non-Goals:**

- Introducing Temporal, a Workflow definition, Activities, task queues, or another retry system.
- Changing the Inbox schema to persist and replay Activity results. A future Temporal-enablement
  change must add that capability before using these results to drive a Workflow.
- Splitting receipt, quality control, and putaway into new physical warehouse states.
- Renaming `StockReplenishedIntegrationEvent` or changing any external event payload or topic.
- Adding a wake trigger after cancellation releases ATP; that is a separate behavioral change with
  its own fairness and multi-SKU transaction design.
- Adding standalone-move commands merely to exercise nullable `picking_id`.
- Replacing constructors or named factory methods with DDD-named factory classes.

## Decisions

### 1. Keep one transactional facade for the initial allocation attempt

`AllocateOrderUsecase.handle(...)` remains the entry point and transaction owner. It continues to:

1. claim the Inbox message;
2. load untaken `Demand`;
3. call `MovementRecorder.recordOutbound(...)`;
4. call `MovementAssigner.assign(...)`; and
5. publish exactly one outcome Domain Event; and
6. return an `AllocationAttemptResult` describing `ALLOCATED`, `BACKORDERED`, or an idempotent
   no-op.

`MovementAssigner.assign(...)` will stop publishing `OrderAllocationCompleted`. It will return the
allocation outcome after applying and persisting a successful assignment. The use case will publish
`OrderAllocationCompleted` for `ALLOCATED`, or `OrderBackorderRecorded` for either insufficient
outcome. This keeps the complete initial-attempt policy in one place without changing the
transaction. The result complements the Integration Event; it does not replace the event used to
advance ordering's projection.

**Alternative considered:** split demand intake, movement recording, and assignment into separate
use cases or Temporal Activities. Rejected because there is no independently meaningful pause
between them today, and the split would require new recovery and concurrency rules for moves that
were recorded but not yet assigned.

### 2. Extract a reusable backorder wake operation and a separate continuation use case

A `BackorderWaker` application component will own one bounded wake round. It will contain the logic
currently in `ReplenishmentUsecase.wake(...)`:

- verify that the triggering SKU has allocatable stock;
- read one FIFO-bounded page of waiting outbound moves;
- invoke `MovementAssigner.assignAll(...)`;
- publish `OrderAllocationCompleted` for each allocated demand; and
- return a `WakeRoundResult` containing the allocated order ids and whether the number allocated
  reached the continuation bound.

An unsuccessful wake attempt publishes no `OrderBackorderRecorded`, because those moves are already
waiting and no new order outcome occurred.

`BackorderWaker` does not decide how another round is scheduled. In the current Integration Event
mode, the transaction-owning caller publishes `BackorderWakeContinuationRequired` from the returned
result so the Outbox record commits with the assignments. A future Temporal Workflow would use the
same result as its loop condition instead of enabling the continuation consumer.

`ReplenishmentUsecase.handle(...)` will record/complete inbound stock, directly invoke
`BackorderWaker.wake(...)`, publish continuation when requested by the result, and return that result
before its transaction commits. A new `WakeBackordersUsecase` will claim a continuation message,
invoke the same component in its own transaction, publish the next continuation when requested,
and return the result. The continuation Kafka handler will depend on `WakeBackordersUsecase`, not
`ReplenishmentUsecase`.

**Alternative considered:** publish an availability event after receipt and wake asynchronously.
Rejected because the commit gap permits a new order to overtake the existing FIFO queue.

**Alternative considered:** keep `handleWake(...)` on `ReplenishmentUsecase`. Rejected because a
continuation receives no stock and should not be coupled to receipt orchestration.

### 3. Keep `MovementAssigner` as one assignment coordinator, but remove event policy from it

`MovementAssigner` will continue to load allocatable batches, invoke the allocation domain service,
apply reservations and move lines, and persist the affected stock and movements. Its single-order
and bounded-batch methods remain shared by the two callers.

The private demand projection, batch loading, and assignment-application helpers remain private
methods for now. They will not become separate classes unless they acquire another caller or an
independent policy. This avoids replacing one cohesive action with several one-method components.

This is also the stopping rule for the application layer. A use case is expected to orchestrate
several direct calls while owning its transaction, Inbox handling, and flow-specific outcome
policy. A step stays as a private method when it has one caller and no independent policy; it
becomes an application component only when multiple use cases must reuse the same cohesive
operation or when it owns a separately testable policy. `BackorderWaker` qualifies because both the
inbound transaction and the continuation transaction invoke the same bounded-round policy.
Components that merely forward one call to another SHALL NOT be introduced.

**Alternative considered:** introduce `StockMoveFactory`, `StockPickingFactory`,
`OutboundOperationCreator`, and `InboundOperationCreator`. Rejected because current constructors and
`StockMove.confirmed(...)` already enforce creation invariants, while repository access and workflow
coordination are application responsibilities rather than Domain Factory responsibilities.

### 4. Keep `MovementRecorder` and make the picking policy explicit

`MovementRecorder` remains an application component with separate `recordOutbound(...)` and
`recordInbound(...)` methods. These supported workflows may create a picking and moves together;
that does not establish a universal move invariant.

The model rules are:

- every inventory movement requires a `StockMove`;
- a generic `StockMove` may have no `pickingId`;
- every order-driven outbound recording creates one picking for that order;
- every move created for that outbound order shares the picking identifier; and
- waiting-demand projection accepts only order-driven outbound picking groups.

No schema migration is needed because `stock_moves.picking_id` is already nullable. Tests and
Javadoc will pin the per-flow policy instead of adding an unused standalone-move API.

### 5. Keep local commands, Domain Events, and Integration Events at their existing boundaries

Direct method invocation composes operations that must share one database transaction. Domain
Events continue to enter synchronous translators that append Integration Events to the Outbox in
that transaction. Kafka Integration Events continue to cross transaction and bounded-context
boundaries, including wake continuation.

A future Temporal integration would wrap `AllocateOrderUsecase`, `ReplenishmentUsecase`, or
`WakeBackordersUsecase` as one Activity per complete transactional facade. It would not call
`MovementRecorder`, `MovementAssigner`, and the outcome publisher as separate Activities, and it
would not move FIFO selection into one Workflow per order.

Business-fact Integration Events such as allocation completion and backorder recording remain even
under Temporal because another bounded context consumes them. The continuation event is different:
it is orchestration control. Integration Event mode consumes it to invoke another wake transaction;
Temporal mode would disable that consumer and let the Workflow invoke the next Activity from
`WakeRoundResult`.

### 6. Treat transaction facades as the interchangeable orchestration seam

Kafka handlers and future Temporal Activities are adapters around the same application boundary:

| Transactional boundary | Integration Event adapter | Future Temporal adapter |
| --- | --- | --- |
| `AllocateOrderUsecase` | maps `OrderPlaced` to `AllocateOrderCommand` | calls one allocation Activity and branches on `AllocationAttemptResult` |
| `ReplenishmentUsecase` | maps `StockReplenishedIntegrationEvent` to `ReplenishStockCommand` | calls one receive-and-first-wake Activity |
| `WakeBackordersUsecase` | maps continuation to `WakeBackordersCommand` | calls one bounded-wake Activity and loops on `WakeRoundResult` |

This does not mean every application component is an Activity. A future split creates another use
case only where a committed checkpoint may be retried, waited on, or compensated independently.
Steps that must commit atomically remain direct calls beneath one use case.

The returned results make the seam visible, but they are not by themselves sufficient for Temporal
retry semantics. Before a Temporal adapter is enabled, its deterministic invocation id and result
must be persisted atomically or reconstructed authoritatively so that a retry after commit returns
the original result. The present boolean Inbox contract remains unchanged in this refactor because
no Temporal worker consumes these boundaries yet.

## Risks / Trade-offs

- **[Risk] Moving success publication out of `MovementAssigner` changes event counts or ordering.**
  → Keep publication inside the same transaction and add tests asserting exactly one outcome for an
  initial attempt that has outstanding demand, exactly one completion per successful wake
  assignment, and no repeated
  backorder event on an unsuccessful wake.
- **[Risk] Extracting wake logic accidentally introduces a new transaction.**
  → Put transaction ownership only on the two use cases; `BackorderWaker` has no propagation
  annotation and is invoked directly.
- **[Risk] Optional picking is interpreted as optional for order-driven outbound work.**
  → Preserve the current recorder behavior and add tests that all moves for one order share one
  non-null picking while different orders never share it.
- **[Risk] Keeping helper methods inside `MovementAssigner` leaves a relatively large class.**
  → Prefer cohesion over speculative classes; extract only when a helper gains a second caller or
  separate policy.
- **[Risk] Explicit results are mistaken for complete Temporal readiness.**
  → Document and test transport neutrality now, but require durable result replay in the later
  Temporal-enablement change before an Activity may branch a Workflow on those results.
- **[Trade-off] Cancellation release still does not wake waiting moves.**
  → Record it as a separate functional change rather than hiding it inside this behavior-preserving
  refactor.

## Migration Plan

1. Pin current initial-attempt, wake-round, event-count, picking-group, FIFO, and transaction
   behavior with focused unit tests.
2. Remove outcome publication from `MovementAssigner` and move it to the two application-flow
   owners.
3. Extract `BackorderWaker`, return explicit attempt/round results, add `WakeBackordersUsecase`, and
   rewire the continuation handler while keeping continuation publication in the transaction owner.
4. Clarify movement/picking contracts in tests and Javadoc without changing the schema.
5. Run unit tests, architecture checks, SIT compilation, and the FIFO/hot-SKU integration suites.
6. Update the living stock-reservation design. Leave archived OpenSpec and `docs/done` unchanged.

Rollback is a code-only revert: there is no database migration, external contract change, or new
runtime dependency.

## Open Questions

None for this change. Receipt-versus-putaway modeling, cancellation-triggered wake, and Temporal
runtime orchestration—including durable Activity-result replay—require separate proposals because
each changes observable workflow or persistence behavior.
