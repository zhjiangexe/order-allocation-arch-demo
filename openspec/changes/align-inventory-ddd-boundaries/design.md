## Context

The current Inventory runtime path has the right operational shape:

```text
candidate selection -> immutable demand/supply -> pure planning -> transactional commit
```

`StockOperationAssignmentCoordinator` selects work, `StockAllocationPlanner` produces a proposal without persistence, and
`StockAllocationCommitter` locks and revalidates authoritative state before writing move lines, quant reservations and lifecycle state.
The remaining problem is structural rather than algorithmic. Packages still describe a mixture of technical categories and obsolete
aggregate assumptions:

- `StockOperation`, `StockMove` and `StockMoveLine` are persisted separately and are coordinated transactionally, but their packages
  call them aggregate/entity types and their repositories are domain aggregate repositories.
- assignment-only queue and predecessor projections are domain value objects even though the planner never sees them;
- application output ports are scattered among `query`, `receipt` and `service` packages;
- the operator read path returns mutable `StockQuant` aggregates inside immutable collections;
- application publication factories import versioned integration contracts; and
- pure domain services and application views retain Spring or Jackson dependencies.

This design applies the approved combination: pragmatic record-centric DDD, feature-first application packages and application
publisher ports with infrastructure-owned contract translation. Existing DB tables, external contracts and transactional behavior are
constraints, not migration targets.

## Goals / Non-Goals

**Goals:**

- Make package ownership reveal whether a type is a domain consistency owner, a domain planning model, an application workflow model,
  an application output port, an entrypoint or an infrastructure adapter.
- Keep the allocation expression visibly equivalent to
  `StockOperationDemand + StockAllocationSupply -> StockAllocationProposal -> commit`.
- Model `StockOperation`, `StockMove` and `StockMoveLine` honestly as independently persisted lifecycle records whose cross-record
  invariants are enforced by application transaction boundaries.
- Keep `StockQuant` and `StockOperationCancellation` as true aggregates with domain-owned repositories.
- Prevent read paths from exposing mutable aggregates and prevent application code from importing transport contracts.
- Preserve query ordering, lock ordering, Outbox atomicity and all observable behavior.

**Non-Goals:**

- Do not change FEFO, SHIP_COMPLETE, FIFO predecessor, retry, replay or cancellation policy.
- Do not introduce a generic repository, generic workflow engine, event bus abstraction, unit-of-work framework or persisted allocation
  aggregate.
- Do not merge `StockOperation`, `StockMove` and `StockMoveLine` into one eagerly loaded aggregate.
- Do not add WMS wave, task, operator, packing, posting or actual-execution models.
- Do not rename DB tables, external REST fields, integration events or lifecycle states.
- Do not optimize or combine existing assignment SQL as part of package alignment.

## Decisions

### 1. Classify domain types by consistency ownership, not table relationship

`StockOperation`, `StockMove` and `StockMoveLine` remain domain models with validation and lifecycle behavior, but they are not presented
as one aggregate hierarchy. They are independently persisted records: an operation summarizes its moves, a move owns requested
quantity and state, and a move line records one committed move-to-quant allocation. Application services lock, validate and update the
complete `StockOperationComposite` in a defined order.

They move from `domain/aggregate` and `domain/entity` to concept-oriented model packages:

```text
movement/domain/model/
├── operation/
│   ├── StockOperation
│   ├── StockOperationState
│   ├── StockOperationSource
│   ├── MovementAssignmentPolicy
│   └── MovementSourceType
└── move/
    ├── StockMove
    ├── StockMoveLine
    └── MoveState
```

Their existing state-transition methods remain on the records. Cross-record rules remain in registration, assignment, release,
cancellation and completion application transactions rather than being hidden in a large operation aggregate.

True consistency owners remain explicit:

- `balance/domain/aggregate/StockQuant` owns `onHandQuantity`, `reservedQuantity` and optimistic consistency;
- `allocation/domain/aggregate/StockOperationCancellation` owns durable cancellation progress; and
- aggregate repository interfaces remain under their corresponding `domain/repository` packages.

Alternative considered: make `StockOperation` the aggregate root and load all moves and move lines through it. Rejected because move
lines can grow substantially, assignment locks quants independently, and future WMS-scale execution requires line-level processing
without hydrating an unbounded object graph.

Alternative considered: turn all movement records into anemic persistence DTOs. Rejected because individual lifecycle transitions and
registration invariants are still domain behavior even though cross-record atomicity belongs to an application transaction.

### 2. Put record persistence ports in Application and aggregate repositories in Domain

Repository location follows what the repository reconstructs:

| Repository role | Owner |
| --- | --- |
| Reconstruct and save a true aggregate | `domain/repository` |
| Persist or lock independently stored lifecycle records for use cases | `application/.../port` |
| Return an assignment candidate, backlog key, supply or operator view | owning application feature's `port` |
| Execute JDBC/JPA/Outbox details | `infrastructure` |

Consequently, `StockQuantRepository` and `StockOperationCancellationRepository` stay in Domain. `StockOperationRepository` and
`StockMoveRepository` become movement application persistence ports because they expose record-oriented operations such as
`saveLines`, `findLinesOf`, group locks and bulk saves. They may live at a movement application shared port because registration and
multiple lifecycle features legitimately share them; feature-first organization does not require duplicate interfaces for identical
semantics.

Focused read repositories keep the `Repository` suffix and full type-derived field names. They do not move to Domain merely because
they are interfaces. This retains the established naming convention without pretending that a candidate projection is an aggregate.

Alternative considered: keep every `Repository` interface in Domain. Rejected because query projections and application-specific
locking scripts would force orchestration concerns into the domain model.

Alternative considered: define a separate repository interface per use case regardless of overlap. Rejected because duplicating the
same persistence semantics would create adapter fan-out without creating a new boundary.

### 3. Organize Application by feature, with one narrow shared-port exception

The target package shape is feature-first:

```text
allocation/application/
├── intake/order/
│   ├── model/
│   ├── port/OrderStockMovementSource
│   └── AllocateOrderUsecase
├── assignment/
│   ├── model/
│   ├── port/
│   ├── StockOperationAssignmentCoordinator
│   ├── StockAllocationCommitter
│   └── StockOperationBacklogReconciler
├── cancellation/
│   ├── model/
│   ├── port/
│   └── ...use cases and transactions
└── lifecycle/
    ├── model/
    ├── port/
    └── ...use cases

balance/application/
├── receipt/{model,port,ConfirmStockReceiptUsecase,...}
└── stockview/{model,port,GetStockQuantUsecase}

movement/application/
├── registration/{model,port,StockOperationRegistrar}
├── lifecycle/{model,port,...}
├── stockoperationview/{model,port,...}
└── shared/port/{StockOperationRepository,StockMoveRepository}
```

Commands, results and application enums move with the feature that gives them meaning. A capability-wide `dto`, `enum`, `type`,
`query`, `result` or `service` dumping package is prohibited. A shared package is allowed only when at least two real application
features consume the same contract and splitting it would duplicate semantics.

`AssignmentQueueKey`, `StockOperationPredecessor`, `StockOperationAssignmentCandidate` and
`StockOperationAssignmentResult` belong to `allocation/application/assignment/model`. The first two remain validated immutable value
objects; moving them out of Domain does not turn them into mutable DTOs. `StockOperationDemand`, `StockMoveDemand`,
`StockAllocationSupply`, `StockQuantSupply`, `StockAllocationProposal` and `ProposedMoveLine` remain domain planning models because they
form the pure planner contract.

The order-source port moves to `allocation/application/intake/order/port`, beside the order-intake use case that consumes it. The
source-neutral registrar remains in `movement/application/registration`; the movement capability does not own an order-specific source
port merely because its normalized output eventually becomes movement registration.

Alternative considered: retain horizontal `command/query/result/service` packages. Rejected because one feature is currently spread
across several unrelated directories and future assignment policies would amplify that navigation cost.

### 4. Return a flat immutable stock projection and group only at the REST boundary

`StockQuantViewRepository` returns an ordered `List<StockQuantView>`, not `Map<String, List<StockQuant>>`. The projection is an
application record containing only the fields required by the stock-location query:

```text
stockQuantId, skuCode, inDate, expiryDate, onHandQuantity, reservedQuantity
```

It exposes pure derived methods for available-to-promise and expiry on a supplied business date. The JDBC adapter constructs the
record directly and no longer creates a mutable `StockQuant` or selects unused owner, location and version columns. `List.copyOf` or
the immutable result of `Stream.toList()` protects the collection; each element is immutable by construction.

The SQL remains one query ordered by `sku_code`, `expiry_date`, `in_date`, and `id`. `StockQuantResponse` groups this ordered list for
the existing JSON contract and computes presentation fields from the projection and `BusinessClock.today()`. Empty stock remains an
empty successful result and expired stock remains visible.

Alternative considered: return a deeply immutable grouped application response. Rejected because SKU grouping is the current REST
presentation shape; the reusable application projection is the ordered stock-row view.

Alternative considered: reuse `StockQuant` but return defensive copies. Rejected because the read port would still falsely advertise
aggregate behavior and would need to track every future mutable field.

### 5. Publish application facts through ports and translate contracts in infrastructure

The assignment, lifecycle and stock-receipt transaction paths call application-owned publisher ports synchronously before the
transaction returns:

```text
Application result/snapshot
        -> application publisher port
        -> infrastructure messaging adapter
        -> versioned integration event + publication envelope
        -> Outbox in the same transaction
```

The assignment publisher accepts the source-neutral `StockOperationAssignmentResult`; its infrastructure adapter routes supported
source types and performs the current ORDER UUID and contract mapping. Release and cancellation publish a complete lifecycle event
containing `StockOperationLifecycleSnapshot` and an application-owned lifecycle action enum. Completion instead publishes one
`StockOperationCompleted` Application Event; one infrastructure adapter atomically derives both the lifecycle-audit publication and
the fulfillment-completion publication in the caller's transaction. Infrastructure maps lifecycle actions to
`StockOperationLifecycleIntegrationEvent.LifecycleAction`. Because receipt currently returns no result, its feature constructs an
immutable application publication model named `StockAvailabilityIncrease` and passes it through `StockAvailabilityPublisher`;
infrastructure alone maps that model to
`StockAvailabilityIncreasedIntegrationEvent` and its contention/publication metadata.

These publisher ports publish application transaction results, snapshots or purpose-specific immutable publication models. A
publication model is only a port input and SHALL NOT implement or be named as a Domain Event. The ports SHALL NOT reintroduce
aggregate-raised Domain Event collections, Domain Event dispatch, or transaction listeners. Allocation commitment is a cross-record
application transaction, so no single aggregate is treated as the event's owner.

`OrderStockOperationAssignedPublicationFactory` and `StockOperationLifecyclePublicationFactory` move to infrastructure messaging or
are absorbed by those adapters, and the receipt use case's direct availability-contract construction moves into its infrastructure
publisher adapter. `AllocationEventSubscriptions` moves to `entrypoint/messaging` because subscription identity belongs to inbound
transport configuration. Application packages SHALL NOT import `contracts.*`, Jackson, Kafka, Temporal or messaging envelope types.

Alternative considered: let Application continue constructing events while moving only the files. Rejected because package movement
would not remove versioned contract coupling and a contract upgrade would still force application changes.

Alternative considered: publish after transaction commit. Rejected because the current synchronous Outbox write is part of the same
atomic state transition and must remain so.

### 6. Keep Domain and application models framework-neutral

`MovementAssignmentPlanner` loses `@Component`; an Inventory configuration class supplies the `StockAllocationPlanner` bean.
Application query views lose Jackson annotations; REST response records retain transport annotations and translate explicitly.
Infrastructure implementations retain Spring stereotypes, transaction support, JDBC/JPA and messaging dependencies.

Architecture tests enforce dependency direction semantically rather than relying only on folder names:

- Domain must not import Spring, Jackson, application, entrypoint, infrastructure or versioned contracts.
- Application models and ports must not import transport, persistence implementation or versioned contract types.
- Entry points may depend on Application but not on infrastructure implementations.
- Infrastructure adapters implement application/domain ports and may depend inward.
- Read repositories must not return types with mutating aggregate behavior.
- Known application workflow models must not return to Domain packages.

Alternative considered: retain harmless annotations for component scanning convenience. Rejected because they obscure the dependency
rule and make architectural drift difficult to distinguish from deliberate framework code.

### 7. Make cleanup evidence-based and keep cross-context orchestration out of bootstrap

`StockOperationDemandFactory` is removed from production only if production-call searches remain empty; otherwise it moves to the
feature that consumes it. Empty directories are removed after source moves. No untracked or modified file is deleted solely because it
is untracked; deletion requires proving that the type is replaced or has no production/test responsibility.

Monolith cancellation classes that contain cross-context process logic move from generic bootstrap wiring to an explicit fulfillment
orchestration/process-manager package. The monolith composition root retains only bean assembly and adapter selection. This is a source
ownership change, not a new distributed service or saga implementation.

Alternative considered: leave process logic under `bootstrap` because it deploys in the monolith. Rejected because deployment
location does not make business coordination composition code, and future service extraction would inherit the wrong ownership.

## Risks / Trade-offs

- [Large package moves can miss Spring wiring, AspectJ pointcuts, reflection or source-path architecture assertions] -> Search by fully
  qualified name and path, compile after each feature move, and update configuration and architecture tests in the same step.
- [Record-centric persistence permits callers to load partial state] -> Keep repositories behind Application, expose focused methods,
  and require lifecycle mutations to pass through the existing transactional services and `StockOperationComposite` validation.
- [Publisher extraction could move Outbox writes outside the state transaction] -> Keep publisher invocation inside the annotated
  commit/lifecycle method and verify atomic rollback in integration tests.
- [Contract translation may drift while results remain source-neutral] -> Preserve the current payload contract tests and add adapter
  mapping tests for every field and unsupported source type.
- [A flat stock projection could change SKU or batch ordering] -> Preserve the exact SQL order and assert REST ordering and empty/
  expired behavior before and after the refactor.
- [Feature-first packaging can produce many one-class packages] -> Create subpackages only for meaningful `model` and `port`
  boundaries; keep use cases and cohesive services at the feature root.
- [Mechanical cleanup can collide with the current dirty worktree] -> Move only files named in the approved task list, preserve
  unrelated changes, and review scoped diffs after each phase.

## Migration Plan

1. Add or update architecture tests to describe the target dependency rules and establish behavior baselines for stock view and event
   publication.
2. Introduce `StockQuantView`, change the stock-view port and JDBC adapter, and update REST mapping while preserving the single-query
   contract.
3. Move assignment models, repositories and services into the feature-first assignment package; then align cancellation, lifecycle,
   receipt, registration and view features without changing method behavior.
4. Reclassify movement records and move their persistence repositories to movement Application, updating all adapters and composition
   wiring atomically.
5. Introduce assignment, lifecycle and stock-availability publisher ports, move contract translation to infrastructure messaging and
   relocate subscription identities to the messaging entrypoint without introducing Domain Events.
6. Remove domain/application framework leaks, relocate cross-context process managers, and remove only proven dead helpers and empty
   package remnants.
7. Run Palantir formatting, architecture and unit tests, Inventory integration/SIT tests, full backend tests and E2E tests.

Rollback is a source-only revert of this change. There is no database, data or external-contract rollback because none is modified.

## Open Questions

None. The model, package and messaging boundary choices were explicitly selected as options `1B`, `2B` and `3B` before this design.
