## 1. Baseline and ownership inventory

- [x] 1.1 Record every Inventory production/test/fixture/SIT type, package-sensitive string, AspectJ expression and direct caller, and map each item to Movement, Allocation, Reservation, Position, Location, cross-domain Application, read side or technical infrastructure.
- [x] 1.2 Record the existing core-type and use-case file inventory so post-refactor verification can prove that package moves or renames did not remove implemented capabilities.
- [x] 1.3 Run the current Inventory unit/architecture tests, representative persistence/SIT tests and compile all direct caller modules to establish a green baseline before ownership changes.
- [x] 1.4 Confirm from the scoped diff that Flyway migrations, table/column mappings, REST/event/Temporal/WMS contracts, algorithms, transaction annotations and unrelated dirty-worktree files are outside this change.

## 2. Replace four-capability assumptions in architecture tests

- [x] 2.1 Rewrite Inventory architecture fitness functions around one bounded context and five domain modules, preserving context/table isolation, inward dependency, framework-free Domain, transport-free Application and explicit-port rules.
- [x] 2.2 Remove rules that require the retired `balance`, `warehouse`, `allocation.lifecycle` or `allocation.support` ownership, exact production file lists, method-body/comment fragments, local names or incidental class morphology.
- [x] 2.3 Add rules that prevent business types from entering context-level observability/entrypoint exceptions and prevent empty Posting, Traceability, Inventory Control or Availability placeholder abstractions.
- [x] 2.4 Run focused architecture tests and demonstrate that a legal class-shape change passes while a forbidden cross-module dependency fails.

## 3. Establish Location and Movement configuration ownership

- [x] 3.1 Move `StockLocation`, `LocationUsageType`, repository, persistence, listing use case, REST entrypoint/response and all tests/callers from `warehouse/location` to the top-level Location module.
- [x] 3.2 Move `StockOperationType`, `StockOperationDirection`, repository and persistence from `warehouse/operationtype` to Movement Operation Type without changing persisted values or database mappings.
- [x] 3.3 Update Movement registration, Allocation projections, Position receipt, fixtures, seed data, monolith/SIT and WMS callers to import the new owners.
- [x] 3.4 Remove only empty retired `inventory/warehouse` directories and prove no production/test/package-sensitive reference to the old ownership remains.
- [x] 3.5 Compile and run Stock Location, Movement registration, schema/persistence and architecture tests.

## 4. Replace Balance with Position ownership

- [x] 4.1 Move `StockQuant`, `StockQuantRepository`, `StockWriteOrder`, entity, mapper, JPA repository and adapter from `balance/onhand` to `position/onhand` without changing the `stock_pools` mapping or quantity behavior.
- [x] 4.2 Move receipt commands, request journal, façade/use cases, ports/adapters, controller and tests from `balance/receipt` to `position/receipt` without changing transaction or availability publication timing.
- [x] 4.3 Move Stock Quant query models, repository port/JDBC adapter, REST endpoint/response and tests from `balance/visibility` to `position/visibility` without changing SQL or JSON.
- [x] 4.4 Update all Allocation, Movement, fixtures, seed data, monolith/SIT and documentation callers; remove only empty retired `inventory/balance` directories and prove no old reference remains.
- [x] 4.5 Run Stock Quant domain/persistence, receipt, visibility, inbound transaction and architecture tests.

## 5. Narrow Allocation to planning and selection

- [x] 5.1 Move demand/supply/proposal values, `StockAllocationPlanner`, `MovementAssignmentPlanner` and planner configuration into `allocation/planning/domain` and its required infrastructure.
- [x] 5.2 Move candidate, predecessor, queue/backlog values and candidate/supply/backlog repository ports/adapters into Allocation Planning Application ownership while retaining existing JDBC projections, predicates and ordering.
- [x] 5.3 Keep `MovementAssignmentPolicy` in Movement intent and update Allocation to consume it without introducing a shared-kernel package.
- [x] 5.4 Run planner, proposal, supply, FIFO/FEFO candidate/backlog and architecture tests, proving planning performs no authoritative writes.

## 6. Establish Reservation assignment and release ownership

- [x] 6.1 Create Reservation Intake, Assignment and Release package slices and move Order intake, assignment coordinator/working models/result publication, wake-up/reconciliation and release use cases to their authoritative owners.
- [x] 6.2 Move `StockMoveLine` to Reservation ownership while preserving its class name, validation, persistence identity, schema mapping and create/delete lifecycle.
- [x] 6.3 Extract a Reservation-owned `StockMoveLineRepository` Application port for save/find/delete operations and leave `StockMoveRepository` responsible only for Stock Moves.
- [x] 6.4 Move or split the existing Move Line JPA repository, entity mapping and adapter behind the new port without changing SQL, flush behavior, constraints, lock order or transaction ownership.
- [x] 6.5 Update `StockAllocationCommitter`, release, completion, cancellation and receipt workflows to use the new Reservation port while preserving proposal revalidation and exact-coverage invariants.
- [x] 6.6 Run assignment planner/coordinator/committer, release, concurrency/rollback, Move Line persistence/schema and architecture tests.

## 7. Move completion, lifecycle publication and cancellation to Movement

- [x] 7.1 Move `StockOperationComposite` and `StockOperationLifecycleSnapshot` into Movement Application ownership and update Reservation consumers without giving either working model persistence identity or repository.
- [x] 7.2 Move complete-operation/source commands, results and use cases plus lifecycle action/publisher/publication adapter into Movement completion/operation ownership without introducing Posting.
- [x] 7.3 Move the durable Stock Operation cancellation model/repository, transactions/use cases, WMS cancellation port/adapter and event entrypoint from Allocation to Movement Cancellation.
- [x] 7.4 Rename `AllocationCancellationState` to `StockOperationCancellationState` and update Java references while preserving database enum strings, keys, checkpoints and replay behavior.
- [x] 7.5 Run completion, lifecycle publication, cancellation, WMS handoff/cancellation, persistence and architecture tests.

## 8. Relocate cross-workflow technical concerns and direct callers

- [x] 8.1 Split stable subscription identities from `AllocationEventSubscriptions` into Reservation Intake, Reservation Assignment and Movement Cancellation entrypoint ownership without changing subscriber IDs.
- [x] 8.2 Move the cross-workflow optimistic-lock retry observer to context-level Inventory observability infrastructure and keep its metrics, log fields, retry classification and wiring unchanged.
- [x] 8.3 Update the context-level Temporal adapter, monolith composition/configuration, WMS, fulfillment workflow runtime/contracts, fixtures, SIT utilities and source-path/AspectJ references to the new packages.
- [x] 8.4 Remove only obsolete empty packages, superseded mixed ports and fully replaced technical holders; compare against the baseline inventory to prove every implemented core model and use case remains.
- [x] 8.5 Update living architecture documentation and package diagrams to distinguish five domain modules, cross-domain workflows/read sides and future extension seams.

## 9. Format and verify behavior preservation

- [x] 9.1 Run `cd backend && ./gradlew spotlessApply`, inspect all non-package/non-import production diffs for unauthorized behavior changes, then run `./gradlew spotlessCheck` and `git diff --check`.
- [x] 9.2 Run all Inventory unit and architecture tests plus all Inventory monolith SIT tests, including persistence, transaction, concurrency, rollback, event-chain, receipt, completion and cancellation scenarios.
- [x] 9.3 Run the complete backend test suite and resolve every cross-module compile or behavioral regression without adding compatibility wrappers.
- [x] 9.4 Run the complete project E2E suite and verify Events, Temporal, WMS, order allocation, availability wake-up, receipt, cancellation, completion and stock-view scenarios.
- [x] 9.5 Run strict OpenSpec validation and verify no migration, database representation, public contract, algorithm, lock order, transaction or externally observable behavior changed.
- [x] 9.6 Compare the final core-type/use-case inventory with the baseline and document every logical rename/removal so a Git delete/add is never mistaken for an erased capability.
