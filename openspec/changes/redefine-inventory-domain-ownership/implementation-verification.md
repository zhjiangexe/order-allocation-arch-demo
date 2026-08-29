# Implementation verification

Verified after applying `redefine-inventory-domain-ownership` on 2026-08-29.

## Inventory comparison

| Item | Baseline | Final | Result |
|---|---:|---:|---|
| Inventory production/test/fixture Java files | 198 | 203 | Five net additions, all explained below |
| Baseline types missing by file name | — | 2 | Both are intentional logical replacements |
| New type file names | — | 7 | One rename, one holder split and one repository extraction |
| Protected migration/contract surfaces | 119 | 119 unchanged | SHA-256 comparison passed |
| Retired package references in backend/docs | present at baseline | 0 | Search passed |
| Empty Inventory source packages | — | 0 | Search passed |

The other 196 baseline file names remain present. Their package and source paths changed to express the five domain
modules, but their implemented capabilities were retained.

## Logical replacements

### Cancellation state rename

- Removed name: `AllocationCancellationState`
- Replacement: `StockOperationCancellationState`
- Meaning: the durable state belongs to Movement Cancellation rather than Allocation.
- Preservation: persisted enum strings, keys, checkpoints and replay behavior are unchanged.

### Subscription identity holder split

- Removed holder: `AllocationEventSubscriptions`
- Replacements:
  - `ReservationIntakeEventSubscriptions`
  - `ReservationAssignmentEventSubscriptions`
  - `MovementCancellationEventSubscriptions`
- Meaning: each subscriber identity is owned by its consuming entrypoint instead of a mixed Allocation holder.
- Preservation: `allocation-ordering-events`, `allocation-inventory-events` and `allocation-order-cancellations` are
  unchanged.

### Stock Move Line persistence boundary extraction

- Added port: `StockMoveLineRepository`
- Added mapper: `StockMoveLineMapper`
- Added adapter: `StockMoveLineRepositoryImpl`
- Meaning: Reservation owns authoritative Move Line persistence while Movement's `StockMoveRepository` owns only
  Stock Moves.
- Preservation: the existing entity/JPA repository and schema remain in use; SQL behavior, flush timing, lock order,
  create/delete lifecycle and transaction ownership are unchanged.

## Removed structures

Only empty retired package directories and superseded mixed holders/operations were removed. No implemented domain
model, use case, externally visible contract, database representation or algorithm was removed. The complete backend
unit suite, Inventory SIT suite and project E2E suite passed after the ownership change.
