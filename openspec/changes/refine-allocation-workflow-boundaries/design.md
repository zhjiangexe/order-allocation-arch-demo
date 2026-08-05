## Context

The initial allocation path currently claims an inbound message, reads untaken demand, records an
outbound picking and its moves, assigns stock, and records the outcome in one transaction.
The stock-receipt input records and completes an inbound movement before it wakes waiting outbound
moves. `StockPool` is the stock context's physical inventory source of truth, so that movement is
the local audit trail rather than an external projection artifact. Both allocation paths reuse `MovementAssigner`, but that component also owns
outcome publication, while the initial use case alone publishes the failure outcome. The resulting
event policy is correct but distributed across classes.

The movement model already permits a null `picking_id`, matching the rule that a move is the
required execution fact and a picking is only a work grouping. Order-driven outbound execution
relies on that picking as its ship-complete group, so making picking optional globally must not make
it optional for that flow. A locally confirmed receipt is warehouse work and therefore must be
represented by an inbound picking, move, and move line before it changes physical stock.

Receipt confirmation and backorder allocation are separate operational checkpoints. Once a receipt
commits, the stock is available even if allocation has not yet run. Fairness therefore cannot rely
on a receipt transaction invoking the waiting queue: every allocation trigger must use the same
queue and policy, while an Outbox event provides a prompt attempt and a scheduler reconciles missed
or failed attempts. The initial-order path also checks the head of every shared SKU queue before it
reserves stock, so a new order arriving in the commit gap cannot overtake an older waiting picking.

The current command records are already independent of Kafka event classes. Initial allocation
needs no direct result because its Kafka caller observes business outcomes through Integration
Events. A wake round remains bounded, but current Kafka and scheduler adapters do not branch on its
result. The Inbox remembers only that an event id was processed, so any future Activity result used
for Workflow branching must be introduced together with durable replay: if the database commits
and Activity completion is lost, a retry must recover the same result.

## Goals / Non-Goals

**Goals:**

- Make the initial-attempt and wake-round outcome policies visible at their application-flow
  boundaries.
- Reuse one assignment implementation without making initial allocation and wake continuation call
  each other's use cases.
- Give availability events and scheduled reconciliation one transactional wake use case without
  making receipt confirmation invoke it directly.
- Keep commands, in-process results, and transactional use cases free of Kafka and Temporal types.
- Keep execution of one bounded wake round separate from the adapters that trigger it.
- Keep movement creation simple and document precisely where picking is optional or required.
- Use `Facility` as the shared term for a physical logistics operation site and reserve
  `StockLocation` for inventory and movement endpoints within or associated with that facility.
- Preserve all observable allocation, event, FIFO/FEFO, Inbox/Outbox, and persistence behavior.
- Leave a coarse-grained, idempotent application boundary that a future Temporal Activity could
  invoke.

**Non-Goals:**

- Introducing Temporal, a Workflow definition, Activities, task queues, or another retry system.
- Changing the Inbox schema to persist and replay Activity results. A future Temporal-enablement
  change must add that capability when it introduces a result used to drive a Workflow.
- Implementing multi-step appointment, unloading, quality-control, or putaway workflows. The
  current stock UI confirms a simplified one-step receipt directly into a caller-selected internal
  location of the facility.
- Renaming Kafka topics. Unreleased event types and HTTP demo contracts may be renamed directly.
- Adding a wake trigger after cancellation releases ATP; that is a separate behavioral change with
  its own fairness and multi-SKU transaction design.
- Adding standalone-move commands merely to exercise nullable `picking_id`.
- Replacing constructors or named factory methods with DDD-named factory classes.

## Decisions

### 1. Keep one transactional facade for the initial allocation attempt

`AllocateOrderUsecase.handle(...)` remains the entry point and transaction owner. It continues to:

1. claim the Inbox message;
2. load untaken `Demand`;
3. call `StockOperationRecorder.recordOutbound(...)`;
4. call `MovementAssigner.assign(...)`; and
5. publish exactly one outcome Domain Event.

`MovementAssigner.assign(...)` will stop publishing `OrderAllocationCompleted`. It will return the
allocation outcome after applying and persisting a successful assignment. The use case will publish
`OrderAllocationCompleted` for `ALLOCATED`, or `OrderBackorderRecorded` for either insufficient
outcome. This keeps the complete initial-attempt policy in one place without changing the
transaction. The Integration Event remains the observable result used to advance ordering's
projection. A direct branching result is deliberately deferred until a Temporal adapter can also
guarantee durable replay of that result.

**Alternative considered:** split demand intake, movement recording, and assignment into separate
use cases or Temporal Activities. Rejected because there is no independently meaningful pause
between them today, and the split would require new recovery and concurrency rules for moves that
were recorded but not yet assigned.

### 2. Let the shared trigger-neutral use case own one bounded waiting-allocation round

`AllocateWaitingDemandUsecase` owns one bounded waiting-allocation round. It contains the logic
formerly held by `ReplenishmentUsecase.wake(...)`:

- verify that the triggering SKU has allocatable stock;
- read one FIFO-bounded page of waiting outbound moves;
- invoke `MovementAssigner.assignWaitingBatch(...)`;
- publish `OrderAllocationCompleted` for each allocated demand.

An unsuccessful wake attempt publishes no `OrderBackorderRecorded`, because those moves are already
waiting and no new order outcome occurred.

The use case does not decide how another round is scheduled. It does
not publish a continuation event: the availability event provides a prompt first round and the
periodic scheduler discovers any eligible scope that remains. A future Temporal Workflow may use a
durably replayable result as its loop condition instead of relying on the scheduler cadence.

`ConfirmStockReceiptUsecase.handle(...)` records and completes one inbound receipt. Completing the
move creates the move line and changes `StockPool`; the use case does not write the physical
quantity independently. It then publishes `StockAvailabilityIncreased`, whose translator appends
a keyed Integration Event to the Outbox in that receipt transaction. It does not invoke
`AllocateWaitingDemandUsecase` and does not return an allocation-round result.

`AllocateWaitingDemandUsecase` owns each bounded allocation transaction. The availability-event handler
claims its message through the Inbox before invoking it; a periodic `AllocationReconciliationScheduler`
discovers bounded waiting scopes and invokes the same use case without transport metadata. Both
triggers invoke the same bounded-round implementation and publish completion facts. A full productive round
leaves the remaining waiting scope for a later scheduler scan instead of emitting orchestration
control through the Outbox.

The REST controller maps HTTP fields, including the selected `locationId`, and the caller-provided
receipt id into the application command and Inbox metadata, then invokes only
`ConfirmStockReceiptUsecase`. The use case verifies through the catalog repository that the
location is internal and belongs to the stated facility. This keeps repository access and
authoritative validation out of the transport adapter while allowing one facility to expose
multiple receipt locations to the UI.

**Alternative considered:** keep the first wake in the receipt transaction. Rejected because it
couples inbound latency and rollback behavior to an outbound queue whose processing policy, load,
and operating cadence vary independently in a 3PL. Fairness is enforced at allocation entry rather
than by holding the receipt transaction open.

**Alternative considered:** keep `handleWake(...)` on the availability-increase use case. Rejected
because a continuation receives no stock and should not be coupled to the input transaction.

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
becomes an application component only when multiple transaction owners must reuse the same cohesive
operation or when it owns a separately testable policy. The bounded waiting-allocation round has
only `AllocateWaitingDemandUsecase` as its transaction owner, so a forwarding component is not retained.
Components that merely forward one call to another SHALL NOT be introduced.

**Alternative considered:** introduce `StockMoveFactory`, `StockPickingFactory`,
`OutboundOperationCreator`, and `InboundOperationCreator`. Rejected because current constructors and
`StockMove.confirmed(...)` already enforce creation invariants, while repository access and workflow
coordination are application responsibilities rather than Domain Factory responsibilities.

### 4. Keep `StockOperationRecorder` and make the picking policy explicit

`StockOperationRecorder` remains an application component for both `recordOutbound(...)` and
`recordInbound(...)`. The inputs remain separate because order demand and a receipt confirmation
have different business shapes. `MovementCompleter` owns the transition from recorded inbound work
to move lines, completed movement state, and physical `StockPool` quantity.

The model rules are:

- every locally managed inventory movement requires a `StockMove`;
- a generic `StockMove` may have no `pickingId`;
- every order-driven outbound recording creates one picking for that order;
- every move created for that outbound order shares the picking identifier; and
- waiting-demand projection accepts only order-driven outbound picking groups.

No schema migration is needed because `stock_moves.picking_id` is already nullable. Tests and
Javadoc will pin the per-flow policy instead of adding an unused standalone-move API.

### 5. Keep local commands, Domain Events, and Integration Events at their existing boundaries

Direct method invocation composes operations that must share one database transaction. Receipt
confirmation and outbound allocation do not share that requirement: the receipt publishes a
Domain Event whose synchronous translator appends an Integration Event to the Outbox in the
receipt transaction. Kafka handles the prompt wake after commit, while scheduling is an independent
adapter around the same wake use case.

A future Temporal integration would wrap `AllocateOrderUsecase`, `ConfirmStockReceiptUsecase`, or
`AllocateWaitingDemandUsecase` as one Activity per complete transactional facade. It would not call
`StockOperationRecorder`, `MovementAssigner`, and the outcome publisher as separate Activities, and it
would not move FIFO selection into one Workflow per order.

Business-fact Integration Events such as allocation completion and backorder recording remain even
under Temporal because another bounded context consumes them. A backorder continuation would only
be orchestration control, so the current event-plus-scheduler mode deliberately does not publish
one. If Temporal later owns the loop, the Workflow can invoke another Activity after durable result
replay is introduced.

### 6. Treat transaction facades as the interchangeable orchestration seam

Kafka handlers and future Temporal Activities are adapters around the same application boundary:

| Transactional boundary | Integration Event adapter | Future Temporal adapter |
| --- | --- | --- |
| `AllocateOrderUsecase` | maps `OrderPlaced` to `AllocateOrderCommand` | calls one allocation Activity; any branching result is introduced with the Temporal adapter and durable replay |
| `ConfirmStockReceiptUsecase` | synchronous stock REST controller maps an HTTP request to `ConfirmStockReceiptCommand`; its Outbox fact triggers allocation after commit | calls one receipt-confirmation Activity |
| `AllocateWaitingDemandUsecase` | maps availability events to `AllocateWaitingDemandCommand`; the scheduler invokes the same boundary directly | calls one bounded waiting-allocation Activity; branching requires a separately introduced durable result contract |

This does not mean every application component is an Activity. A future split creates another use
case only where a committed checkpoint may be retried, waited on, or compensated independently.
Steps that must commit atomically remain direct calls beneath one use case.

No in-process result is returned because neither Kafka nor the scheduler branches on it. Before a
Temporal adapter branches on any Activity result, its deterministic invocation id and result must be
persisted atomically or reconstructed authoritatively so that a retry after commit returns the original
result. The present boolean Inbox contract remains unchanged because no Temporal worker consumes these
boundaries yet.

### 7. Call the physical logistics site a Facility

`Facility` is the catalog identity for a physical logistics operation site, including a warehouse,
distribution center, cross-dock, or another site that may participate in fulfillment. It does not
mean a stock location and it does not prescribe that every facility stores inventory permanently.

All current identifiers for that concept become `facilityId`. The names `nodeId`,
`fulfillmentNodeId`, and `warehouseId` SHALL NOT remain as aliases in current code or contracts.
`StockLocation` and `locationId` remain separate because stock pools and movements require concrete
endpoints. A facility may own multiple internal locations; commands that act on physical stock
therefore identify both the facility policy scope and the concrete location endpoint.

Because the application is not deployed, REST payloads, Integration Event payloads, frontend
clients, and the existing baseline migrations are renamed together. No dual-read fields, deprecated
accessors, or follow-up migration are introduced. Archived changes and completed historical records
retain their original vocabulary.

## Risks / Trade-offs

- **[Risk] Moving success publication out of `MovementAssigner` changes event counts or ordering.**
  → Keep publication inside the same transaction and add tests asserting exactly one outcome for an
  initial attempt that has outstanding demand, exactly one completion per successful wake
  assignment, and no repeated
  backorder event on an unsuccessful wake.
- **[Risk] Waiting-allocation logic accidentally introduces a nested transaction.**
  → Keep the repositories, bounded-round orchestration, completion publication, and transaction
  ownership together in `AllocateWaitingDemandUsecase`.
- **[Risk] Optional picking is interpreted as optional for order-driven outbound work.**
  → Preserve the current recorder behavior and add tests that all moves for one order share one
  non-null picking while different orders never share it.
- **[Risk] Keeping helper methods inside `MovementAssigner` leaves a relatively large class.**
  → Prefer cohesion over speculative classes; extract only when a helper gains a second caller or
  separate policy.
- **[Risk] Removing continuation increases time-to-allocation for a backlog larger than one round.**
  → Keep the availability event as the prompt first attempt, tune the bounded scheduler cadence,
  and monitor waiting age; introduce explicit orchestration only when its latency requirement is
  demonstrated.
- **[Risk] `Facility` is treated as another name for `StockLocation`.**
  → Keep both identifiers in commands that need both scopes, and retain location-based stock and
  movement persistence while facility owns site-level policy and contention.
- **[Risk] A direct quantity update bypasses the physical inventory ledger.**
  → Keep `StockPool.receive(StockMoveLine)` as the physical increase capability and require local
  receipt confirmation to create and complete inbound warehouse execution first.
- **[Trade-off] Cancellation release still does not wake waiting moves.**
  → Record it as a separate functional change rather than hiding it inside this behavior-preserving
  refactor.

## Migration Plan

1. Pin current initial-attempt, wake-round, event-count, picking-group, FIFO, and transaction
   behavior with focused unit tests.
2. Remove outcome publication from `MovementAssigner` and move it to the two application-flow
   owners.
3. Add `AllocateWaitingDemandUsecase`, move the bounded waiting-allocation round into it, and let
   availability plus scheduled reconciliation trigger separate transactions without a continuation control event.
4. Clarify movement/picking contracts in tests and Javadoc without changing the schema.
5. Run unit tests, architecture checks, SIT compilation, and the FIFO/hot-SKU integration suites.
6. Rename the unreleased facility model, API/event contracts, migrations, frontend, and tests in one
   step without a compatibility layer.
7. Replace the receipt-shaped replenishment vocabulary with explicit receipt confirmation and a
   transactional use case.
8. Expose the local receipt through a synchronous stock REST controller, restore inbound movement
   recording/completion, and remove the dev Kafka probe path.
9. Update the living stock-reservation design. Leave archived OpenSpec and `docs/done` unchanged.

Rollback is a code-only revert: the event type and dev-only HTTP contract are unreleased, and there
is no database migration or new runtime dependency.

## Open Questions

None for this change. Multi-step receipt/putaway modeling, cancellation-triggered wake, and Temporal
runtime orchestration—including durable Activity-result replay—require separate proposals because
each changes observable workflow or persistence behavior.
