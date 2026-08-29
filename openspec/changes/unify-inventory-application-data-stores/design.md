## Context

Inventory currently uses package location to classify a data port as command- or query-owned and uses `Finder` versus
`Store` to classify each method as read-only versus effectful. This creates paired interfaces for a single persisted
model, duplicates constructor dependencies, and makes the technical CQRS split more prominent than the capability
that owns the data access.

The replacement convention keeps all custom persistence and projection abstractions in Application, but uses one
`Store` vocabulary for command and query access. Domain stays persistence-ignorant and Infrastructure continues to
implement the ports. Command and query remain useful use-case/package concepts; they no longer determine data-port
names or locations.

## Goals / Non-Goals

**Goals:**

- Use `*Store` for every custom Inventory persistence, planning-input and read-model port.
- Place Stores in the owning capability's neutral `application.port` package.
- Merge read and write interfaces when they represent one cohesive persisted model.
- Keep purpose-specific Stores separate when they represent different projections or workflow concerns.
- Reduce constructor and adapter ceremony without weakening Domain/Application/Infrastructure direction.
- Preserve all runtime behavior and persistence semantics.

**Non-Goals:**

- Combining all Inventory access into a generic Store or Repository.
- Moving Spring Data repositories out of Infrastructure.
- Moving transaction boundaries or persistence behavior into Domain.
- Changing allocation rules, SQL, lock order, flush timing, schemas, APIs, messages or workflows.
- Flattening command and query use-case packages.

## Decisions

### 1. Store means an Application-owned data-access capability

`Store` identifies a custom Application port that accesses durable or projected Inventory data. A Store may expose
ordinary reads, immutable projections, transaction locks and mutations when those operations form one cohesive
contract. The suffix no longer promises method effects.

Alternative considered: preserve Finder for reads and Store for effects. Rejected because the user wants one stable
Application data-access vocabulary, and method names (`find`, `inspect`, `lock`, `save`, `delete`, `claim`) already
make the effect visible.

### 2. Neutral Application port packages own the contracts

Every custom Store lives under the owning slice's `application.port` package. Command and query use cases may both
depend on Stores, and neither Store packages nor suffixes encode the consuming side. Use cases and DTOs remain in
their existing `application.command` or `application.query` packages where that classification remains meaningful.

Alternative considered: keep duplicate `application.command.port` and `application.query.port` packages while using
Store everywhere. Rejected because package ownership would still imply an artificial restriction and would prevent a
cohesive port from being shared by both sides.

### 3. Merge only same-model read/write pairs

The following pairs become one Store in a neutral Application port package:

| Current contracts | Unified contract |
|---|---|
| `StockLocationFinder` + `StockLocationStore` | `StockLocationStore` |
| `StockOperationCancellationFinder` + `StockOperationCancellationStore` | `StockOperationCancellationStore` |
| `StockMoveFinder` + `StockMoveStore` | `StockMoveStore` |
| `StockOperationFinder` + `StockOperationStore` | `StockOperationStore` |
| `StockOperationTypeFinder` + `StockOperationTypeStore` | `StockOperationTypeStore` |
| `StockQuantFinder` + `StockQuantStore` | `StockQuantStore` |
| `StockMoveLineFinder` + `StockMoveLineStore` | `StockMoveLineStore` |

Their existing methods move unchanged into the unified contracts. Entity-oriented `*PersistenceAdapter` classes
continue implementing the resulting single Store.

### 4. Focused read and projection ports remain separate Stores

Changing the shared vocabulary does not erase interface segregation. The following focused contracts are renamed,
not merged into entity Stores:

| Current contract | Unified vocabulary |
|---|---|
| `StockAllocationSupplyFinder` | `StockAllocationSupplyStore` |
| `StockOperationAssignmentCandidateFinder` | `StockOperationAssignmentCandidateStore` |
| `StockOperationAssignmentBacklogFinder` | `StockOperationAssignmentBacklogStore` |
| `StockLocationViewFinder` | `StockLocationViewStore` |
| `StockOperationViewFinder` | `StockOperationViewStore` |
| `StockOperationReconciliationFinder` | `StockOperationReconciliationStore` |
| `StockQuantViewFinder` | `StockQuantViewStore` |

`StockReceiptRequestStore` keeps its name and moves to a neutral Application port package. Focused JDBC adapters use
matching `*Store` names; cohesive JPA adapters retain neutral `*PersistenceAdapter` names.

### 5. Store is not an Aggregate Repository

A Store is scoped by the capability or projection it supports, not by a rule that every Store must map one-to-one to
an Aggregate Root. Domain objects still own invariants and behavior, Application still owns orchestration and
transactions, and Infrastructure still owns SQL and persistence frameworks. Spring Data `Jpa*Repository` interfaces
remain Infrastructure details and keep their framework names.

### 6. Architecture enforcement is temporarily deferred

The existing source-level and legacy architecture tests are removed by owner decision after the package flattening.
The Store ownership rules remain design constraints, but their executable enforcement is deferred to a separately
designed ArchUnit suite. Until that replacement exists, source scans and review verify that custom `*Store`
declarations remain Application-owned, custom Inventory `*Finder` declarations stay retired, and custom data-access
abstractions do not return to Domain.

## Risks / Trade-offs

- [Risk] `Store` no longer reveals read versus write from the type name. → Use explicit method verbs and narrow
  purpose-specific interfaces; transaction locks retain `lock...` names.
- [Risk] A unified convention could encourage a giant Store. → Merge only same-model twins and retain focused
  allocation, queue, reconciliation and visibility Stores.
- [Risk] Moving ports can miss bootstrap, fixtures or tests. → Search every backend source set and compile direct
  callers before full tests.
- [Risk] Historical OpenSpec artifacts describe the superseded split. → Preserve them as history and update the main
  capability through this explicit delta.
- [Risk] Existing dirty-worktree changes overlap the same files. → Restrict changes to ports, adapters, dependencies,
  architecture rules and documentation; do not reset unrelated work.

## Migration Plan

1. Record the current Finder/Store inventory and direct callers.
2. Create neutral `application.port` Stores and merge same-model contracts.
3. Rename focused Finder ports and adapters to Store vocabulary.
4. Update production wiring, tests and fixtures atomically.
5. Remove the prior CQRS naming fitness functions, record the temporary enforcement gap and update living
   documentation; recreate stable rules later in a dedicated ArchUnit change.
6. Format and run focused, full backend and E2E verification, then validate and sync OpenSpec.

Rollback is source-only: restore the split interfaces, imports and adapter names. No database or external contract
rollback is required.

## Open Questions

None. The requested convention is Application-owned Store for both command and query access, with interface
segregation based on cohesive capability rather than method effect.
