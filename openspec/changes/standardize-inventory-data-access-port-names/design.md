## Context

Inventory has completed its five-domain ownership refactor, but its custom data-access interfaces still use
`Repository` in both Domain and Application packages. In particular, authoritative command persistence
(`StockOperationRepository`, `StockMoveRepository`, `StockMoveLineRepository`, `StockReceiptRequestRepository`) and
read-only projections (`*ViewRepository`, `*CandidateRepository`, `*BacklogRepository`) look identical by suffix.

The project uses CQRS selectively. A command may call a read-only Finder to obtain planning input; command versus
query therefore describes the port's mutation authority, not the top-level use case that happens to call it.

Spring Data `Jpa*Repository` interfaces are infrastructure implementation details and retain the framework's standard
name. They are not Inventory-owned ports and are outside this vocabulary rule.

## Goals / Non-Goals

**Goals:**

- Make the owner and mutation authority of every Inventory data-access port evident from three suffixes.
- Keep aggregate collection abstractions named `Repository` in Domain.
- Name Application-owned mutable persistence `Store` and read-only access `Finder`.
- Enforce the convention without brittle exact-type or method-body architecture tests.
- Preserve behavior and keep every constructor field named after the complete port type.

**Non-Goals:**

- Changing SQL, entity mappings, transaction annotations, locks, algorithms or public contracts.
- Renaming Spring Data `Jpa*Repository` interfaces.
- Introducing `Reader`, `Writer`, `Persistence`, `Journal`, `Gateway` or layer-prefixed alternatives.
- Moving application persistence ports into Domain merely to retain a `Repository` suffix.

## Decisions

### 1. Use exactly three Inventory data-access port suffixes

| Suffix | Owner | Contract |
|---|---|---|
| `Repository` | Domain | Collection-like persistence abstraction owned by a Domain aggregate/reference model |
| `Store` | Application port | Authoritative mutable state used by a command workflow; may read, lock, save or delete |
| `Finder` | Application port | Read-only snapshot, view, candidate, backlog or reconciliation access; never mutates |

`Store` may expose reads because a safe command commonly loads or locks state before mutation. `Finder` may be called
from a command because allocation planning needs read-only demand and supply inputs.

### 2. Classify current Application ports by mutation authority

| Current port | Final port |
|---|---|
| `StockOperationRepository` | `StockOperationStore` |
| `StockMoveRepository` | `StockMoveStore` |
| `StockMoveLineRepository` | `StockMoveLineStore` |
| `StockReceiptRequestRepository` | `StockReceiptRequestStore` |
| `StockOperationAssignmentBacklogRepository` | `StockOperationAssignmentBacklogFinder` |
| `StockOperationAssignmentCandidateRepository` | `StockOperationAssignmentCandidateFinder` |
| `StockQuantViewRepository` | `StockQuantViewFinder` |
| `StockOperationViewRepository` | `StockOperationViewFinder` |
| `StockOperationReconciliationRepository` | `StockOperationReconciliationFinder` |

`StockAllocationSupplyFinder` already has the correct name; move it from the obsolete
`allocation.application.store` package to `allocation.planning.application.port` so its owner and layer are correct.

Domain `StockLocationRepository`, `StockQuantRepository`, `StockOperationTypeRepository` and
`StockOperationCancellationRepository` retain their names and locations.

### 3. Rename custom adapters after their ports, but retain Spring Data vocabulary

Custom implementations become `StockOperationStoreAdapter`, `StockMoveStoreAdapter`, `StockMoveLineStoreAdapter`,
`JdbcStockReceiptRequestStore` and `Jdbc*Finder`. Spring Data types such as `JpaStockOperationRepository` remain
unchanged because `Repository` there names the framework implementation mechanism rather than an Inventory port.

### 4. Enforce stable semantic rules rather than exact inventories

Architecture tests will reject custom `*Repository` interfaces under Application, `*Store`/`*Finder` interfaces
outside Application port packages and obvious mutator operations on Finder interfaces. They will not assert exact
file lists, constructor shapes, comments, local variable names or method counts.

## Risks / Trade-offs

- [Risk] `Store` may be mistaken for write-only access. → Document and test that command-side load/lock operations are
  allowed; only Finder is strictly read-only.
- [Risk] Infrastructure contains both `Jpa*Repository` and custom `*StoreImpl`/`Jdbc*Finder`. → Treat the prefix and
  implemented port as the distinction and exempt only interfaces extending Spring Data Repository types.
- [Risk] Mechanical renames can miss source strings, test imports or Spring composition. → Search the complete backend
  and docs for every retired type and run full compilation, tests and E2E.
- [Risk] A suffix rule can become an over-specified architecture test. → Assert package ownership and mutation
  authority only, not exact type inventories or implementation morphology.
