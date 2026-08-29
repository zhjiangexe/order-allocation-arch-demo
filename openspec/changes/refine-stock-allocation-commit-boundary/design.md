## Context

`StockOperationAssignmentTransaction` is the authoritative boundary that converts a ready `StockAllocationProposal` into reserved
`StockQuant` counters, persisted `StockMoveLine` rows and `ASSIGNED` move/operation state. Its transaction and locking behavior are
correct, but the class name starts from the resulting state and exposes the transaction mechanism, while its input and business action
are allocation proposal commitment. Its `execute` method also interleaves lock loading, replay handling, stale validation, quant
validation, mutation, result assembly and publication.

The surrounding refactor established a clear pure-planning contract:
`StockOperationDemand + StockAllocationSupply -> StockAllocationProposal`. This change completes the next boundary without changing
that planner or the authoritative stock model.

## Goals / Non-Goals

**Goals:**

- Make the application flow read as a coordinator invoking `planner.plan(...)` followed by `committer.commit(...)`.
- Keep one obvious transactional method whose body exposes the ordered commit stages at a glance.
- Use the unified `Repository` suffix for Inventory persistence ports and full type-derived property names.
- Give the shared move-to-quant mapping a stage-neutral name that remains valid before commit and during release/completion.
- Move assignment-result assembly out of the committer because it is projection/enrichment rather than state mutation.
- Preserve the current lock order, revalidation, idempotent replay, state transitions and Outbox atomicity.

**Non-Goals:**

- Do not change planner policy, FEFO order, predecessor rules, SQL or query count.
- Do not introduce a generic committer interface, strategy router, validation framework or persisted allocation aggregate.
- Do not split one database transaction across services or publish after the transaction returns.
- Do not rename `StockOperationAssignmentResult`, `StockMoveLine`, persisted tables, states, external APIs or integration events.
- Do not change reservation release, cancellation or completion behavior.

## Decisions

### 1. Name the boundary after its business command

Rename `StockOperationAssignmentTransaction` to `StockAllocationCommitter` and `execute(...)` to `commit(...)`. `StockAllocation` names
the proposal being made authoritative; `Committer` names the irreversible application action. The `@Transactional` annotation and
tests continue to guarantee the technical boundary without embedding that mechanism in the business type name.

Alternative considered: `StockAllocationCommitTransaction`. Rejected because `Transaction` still exposes implementation vocabulary and
does not improve the caller expression over `committer.commit(...)`.

Alternative considered: `StockOperationAssigner`. Rejected because assignment orchestration and authoritative allocation mutation are
separate responsibilities; the orchestration role is named `StockOperationAssignmentCoordinator`.

### 2. Keep the commit method as a linear application script

The public method performs these visible stages in order:

1. validate the ready proposal and business times;
2. lock the complete current operation state;
3. reconstruct and return an already-committed replay;
4. validate the proposal and final predecessor against locked state;
5. create the move-to-quant allocation mapping and lock/revalidate selected quants;
6. reserve quants, create move lines and assign moves and operation;
7. assemble the committed result and publish it synchronously.

Small private methods own each stage. No generic pipeline, command object or chain-of-responsibility is introduced: those abstractions
would hide the required ordering instead of clarifying it.

### 3. Use one neutral move-to-quant mapping across lifecycle stages

Rename `QuantReservationSet` to `MoveQuantAllocationSet`. The structure is fundamentally the exact set of
`moveId -> stockQuantId -> quantity` relationships. Before assignment it is derived from `ProposedMoveLine`; after assignment it can be
reconstructed from authoritative `StockMoveLine` for release and completion. Calling both forms a reservation set conflates an intended
reservation with committed reservation truth.

The type continues to validate identity, scope, expiry and available quantity and to calculate the global quant lock/write order. It is
an ephemeral lifecycle working model, never a persisted Allocation or reservation ledger.

Alternative considered: separate `ProposedQuantReservations` and `CommittedQuantReservations`. Rejected because both require the same
mapping invariants, aggregation and lock ordering, and their only difference is the source row type.

### 4. Extract result assembly, retain publication in the transaction

Move operation-type lookup and `StockOperationAssignmentResult` construction to `StockOperationAssignmentResultFactory`. This is one
cohesive projection responsibility and removes sorting/grouping/enrichment details from the mutation boundary. The committer retains
the actual publication call so the Outbox write remains visibly inside `commit(...)`'s transaction.

Alternative considered: extract a publisher service as well. Rejected for now because the existing order-specific publication factory
already isolates contract translation, and another one-method layer would not remove meaningful complexity.

### 5. Use one explicit Repository convention across Inventory persistence ports

Aggregate persistence ports and read-projection ports use the `Repository` suffix. Their full domain role remains visible in the type:
`StockOperationRepository` persists the aggregate, while `StockOperationViewRepository`,
`StockOperationAssignmentCandidateRepository` and `StockAllocationSupplyFinder` expose focused read projections. This restores the
project's original convention without combining command and read methods into one interface.

Repository fields and constructor parameters use the complete lower-camel type name, for example `stockOperationRepository`,
`stockOperationAssignmentCandidateRepository` and `stockAllocationSupplyFinder`. Shortened `store`, `query`, `candidateQuery`,
`supplyQuery`, `moveRepository` and generic `repository` properties are rejected because the dependency role disappears at the call
site. Generic or legacy local names such as `locked`, `snapshot`, `batches`, `usecase`, `assignPicking` and `confirmedPicking` become
names based on their current facts. Messages in the changed path use `stock allocation proposal`, `stock operation` and `stock move`
consistently.

The `Repository` suffix does not collapse CQRS responsibilities: application-query packages, focused methods and JDBC adapter names
continue to identify projection repositories, while domain-repository packages identify aggregate persistence ports.

Broad enum or public contract renames such as `MovementSourceType` are excluded: they need their own semantic decision and should not be
mixed into a commit-method readability refactor.

### 6. Name orchestration and transaction-scoped composition by their actual roles

Rename `StockOperationAssigner` to `StockOperationAssignmentCoordinator`. It coordinates the initial and replenishment-wake entry paths
through candidate selection, supply loading, pure planning and commit, while the planner and committer retain their separate domain and
application-layer responsibilities.

Rename `LockedStockOperation` to `StockOperationComposite`. The type composes one `StockOperation` with its complete `StockMove` and
`StockMoveLine` structure for lifecycle consistency checks. It does not acquire or own database locks, has no identity or repository,
and is neither a value object nor a persisted aggregate. Loader methods use `ForUpdate` vocabulary to keep lock acquisition explicit at
the transaction boundary.

## Risks / Trade-offs

- [A rename can miss reflective pointcuts or architecture source-path assertions] → Search all active code and documentation, update
  AspectJ expressions and architecture tests, and run compile, unit, SIT and E2E coverage.
- [Extracted result assembly could perform lookup outside the transaction] → Invoke the factory synchronously from `commit(...)` before
  publication and transaction return.
- [A shorter public method could hide lock ordering] → Keep stage names explicit and preserve tests asserting operation, move,
  predecessor and quant lock order.
- [Stage-neutral Allocation vocabulary could be mistaken for a new aggregate] → Keep `MoveQuantAllocationSet` under application
  lifecycle working models and document that it has no identity, repository or persistence.
- [A uniform Repository suffix could hide command/read differences] → Retain focused interfaces and packages, use full role names, and
  prohibit a combined catch-all Inventory repository.

## Migration Plan

1. Rename the committer and move-to-quant working model atomically with all callers and tests.
2. Add the result factory and rewrite `commit(...)` around named stages.
3. Update active documentation, architecture checks and residual local/test vocabulary.
4. Run Palantir formatting, Inventory tests, assignment SIT, full backend tests and E2E.

Rollback is a source-only revert. There is no schema, data or external contract migration.

## Open Questions

None.
