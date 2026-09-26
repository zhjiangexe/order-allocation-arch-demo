## Context

The first implementation of this change classified ports only by the consuming use case: every command dependency
became a Store and every query dependency became a Finder. That kept query use cases away from mutation ports but
turned pure allocation SELECTs into Stores and left former Repository read/write contracts largely intact.

The corrected model separates two dimensions:

1. Package ownership identifies the consuming use-case side: `application.command.port` or
   `application.query.port`.
2. The type suffix identifies method semantics: Finder for pure reads and Store for mutations or transaction locks.

A command may therefore use a command-owned Finder and Store. A query uses a query-owned Finder and never a Store.
Domain remains responsible for state and behavior but owns no persistence interfaces.

## Goals / Non-Goals

**Goals:**

- Make every Inventory custom data-access port Application-owned.
- Make every pure read method belong to a Finder.
- Make every state-changing or transaction-locking method belong to a Store.
- Keep command-owned Domain loading and planning inputs distinct from query-owned read models through packages and
  return types.
- Split mixed Repository/Store contracts without duplicating persistence behavior.
- Preserve all observable behavior, persistence ordering and concurrency controls.

**Non-Goals:**

- Changing allocation, FIFO/FEFO, reservation, completion or cancellation behavior.
- Changing SQL predicates, database schemas, mappings, transaction boundaries, locks or public contracts.
- Renaming Spring Data `Jpa*Repository` interfaces.
- Requiring a separate Infrastructure class for every port; one cohesive persistence adapter may implement a Finder
  and Store for the same model.
- Combining focused allocation adapters into one large persistence class.

## Decisions

### 1. Application owns all custom Inventory data-access ports

Domain packages contain state, behavior and policies, not persistence abstractions. Application Finders load Domain
objects or immutable planning input, Application Stores acquire locks and persist command results, and Domain behavior
receives already loaded objects.

Alternative considered: retain classic DDD aggregate Repositories in Domain. Rejected for this Inventory model
because no Domain service invokes persistence and Application already owns the transaction boundary.

### 2. Classify suffixes by method effect

A Finder exposes only observational methods such as `find`, `list`, `inspect`, `exists` or `count`; it never changes
authoritative state or acquires a transaction lock. A Store exposes mutation methods such as `save`, `delete` or
`claim`, plus explicit `lock...` methods whose database effect is required by a command transaction. A Store does not
expose an ordinary `find...` method.

`find...ForUpdate` is renamed to `lock...` and remains in Store because the row lock is a transaction effect. This
classification is based on the port method, not on whether its current caller is a command or query.

### 3. Package ownership and method semantics are independent

- A Finder under `application.command.port` loads a write model, reference model or planning projection required by a
  command.
- A Store under `application.command.port` changes or locks authoritative state.
- A Finder under `application.query.port` serves operator-facing or diagnostic read models.
- No Store exists under `application.query.port`, and query Application code cannot depend on command ports.

This avoids the false choice between calling a pure SELECT a Store and making command code depend on a query-owned
read model.

### 4. Split the existing ports explicitly

| Current port | Corrected ports |
|---|---|
| `StockLocationStore` | command `StockLocationFinder.findById`; `StockLocationStore.save` |
| query `StockLocationFinder` | query `StockLocationViewFinder` |
| `StockQuantStore` | command `StockQuantFinder` for pure reads; `StockQuantStore` for `lock...` and `save` |
| `StockOperationTypeStore` | command `StockOperationTypeFinder`; `StockOperationTypeStore.save` |
| `StockOperationCancellationStore` | command `StockOperationCancellationFinder`; `StockOperationCancellationStore.save` |
| `StockOperationStore` | command `StockOperationFinder`; `StockOperationStore.save` and `lockById` |
| `StockMoveStore` | command `StockMoveFinder`; `StockMoveStore.save`, `saveAll` and `lock...` |
| `StockMoveLineStore` | command `StockMoveLineFinder`; `StockMoveLineStore.saveAll` and `deleteByMoveIds` |
| `StockReceiptRequestStore` | unchanged; `claimIfNew` is an atomic mutation |
| `StockOperationAssignmentCandidateStore` | command `StockOperationAssignmentCandidateFinder` |
| `StockOperationAssignmentBacklogStore` | command `StockOperationAssignmentBacklogFinder` |
| `StockAllocationSupplyStore` | command `StockAllocationSupplyFinder` |
| `StockOperationViewFinder` | unchanged query Finder |
| `StockOperationReconciliationFinder` | unchanged query Finder |
| `StockQuantViewFinder` | unchanged query Finder |

The command Finders may return Domain objects or immutable planning inputs. Query Finders return immutable views,
DTOs or diagnostic projections.

### 5. Use neutral names for adapters implementing both read and write ports

An entity-oriented JPA adapter may implement both the command Finder and Store for the same model because interface
segregation is enforced at the Application boundary. Such implementations use neutral `*PersistenceAdapter` names
instead of pretending the Infrastructure class is only a Store. Focused JDBC allocation projections remain separate
`Jdbc*Finder` adapters.

### 6. Architecture tests enforce method effects and dependency direction

Fitness functions assert that custom Domain packages contain no data-access ports, Store interfaces contain no pure
query method names, Finder interfaces contain no mutation or lock method names, Stores live only in command-port
packages, and Finders live in command- or query-port packages. Query Application code may use only query-owned
Finders. Tests remain non-vacuous without hard-coding the exact interface inventory.

## Risks / Trade-offs

- [Risk] Splitting ports increases constructor dependencies in command services. → Accept the explicit dependencies;
  one Infrastructure adapter may still implement both narrow interfaces.
- [Risk] Finder can be mistaken for an operator read model. → Package and return type distinguish command-owned Domain
  loading from query-owned views.
- [Risk] Locking methods look like reads. → Use the `lock...` verb and keep them in Store.
- [Risk] Broad rename may miss tests, fixtures or bootstrap imports. → Search all backend source sets and compile every
  direct caller module before full tests.
- [Risk] Existing dirty-worktree changes overlap the same files. → Restrict edits to data-port contracts and preserve
  unrelated logic.

## Migration Plan

1. Correct the OpenSpec convention before archive.
2. Introduce command-owned Finders and move pure read methods out of Stores.
3. Rename allocation Stores back to Finders and rename neutral persistence adapters.
4. Update production callers, bootstrap, tests and fixtures atomically.
5. Replace architecture rules and update living documentation.
6. Run formatting, focused tests, full backend tests and E2E.

Rollback is source-level: restore the prior interfaces, imports and adapter names. No database or external contract
rollback is required.

## Open Questions

None. Package ownership and method-effect classification are intentionally separate.
