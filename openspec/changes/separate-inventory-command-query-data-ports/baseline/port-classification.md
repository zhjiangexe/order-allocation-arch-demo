# Pre-change Inventory data-port baseline

Captured on 2026-08-29 before applying `separate-inventory-command-query-data-ports`.

## Custom ports and production ownership

| Current port | Definition | Production consumers | Target |
|---|---|---|---|
| `StockLocationRepository` | `location/domain` | seed, location listing, inbound registration and receipt completion | split `StockLocationStore` / `StockLocationFinder` |
| `StockQuantRepository` | `position/onhand/domain` | seed, assignment, release, receipt and completion | `StockQuantStore` |
| `StockOperationTypeRepository` | `movement/operationtype/domain` | seed, inbound registration and assignment result | `StockOperationTypeStore` |
| `StockOperationCancellationRepository` | `movement/cancellation/domain` | cancellation transaction | `StockOperationCancellationStore` |
| `StockOperationStore` | `movement/operation/application/port` | registration, assignment, release, completion and cancellation | move to command port |
| `StockMoveStore` | `movement/operation/application/port` | registration, assignment, release, completion and cancellation | move to command port |
| `StockMoveLineStore` | `reservation/assignment/application/port` | assignment, release, receipt, completion and cancellation | move to command port |
| `StockReceiptRequestStore` | `position/receipt/application/port` | synchronous receipt command | move to command port |
| `StockOperationAssignmentCandidateFinder` | `allocation/planning/application/port` | assignment coordinator and committer | `StockOperationAssignmentCandidateStore` |
| `StockOperationAssignmentBacklogFinder` | `allocation/planning/application/port` | assignment backlog reconciliation | `StockOperationAssignmentBacklogStore` |
| `StockAllocationSupplyFinder` | `allocation/planning/application/port` | assignment coordinator | `StockAllocationSupplyStore` |
| `StockOperationViewFinder` | `movement/visibility/application/port` | stock-operation query service | move to query port |
| `StockOperationReconciliationFinder` | `movement/visibility/application/port` | stock-operation diagnostic health indicator | move to query port |
| `StockQuantViewFinder` | `position/visibility/application/port` | stock-quant query use case | move to query port |

Spring Data `Jpa*Repository` interfaces are Infrastructure implementation details and are not custom business ports.

## Baseline verification

- `./gradlew :inventory-context:test` completed successfully before editing (42 tasks, all up-to-date).
- The immediately preceding verified Inventory baseline contains 155 unit and architecture tests, all passing.
- The caller inventory was captured with `rg` across Inventory production, test, fixture, monolith bootstrap and SIT
  source sets; the complete raw inventory remains available in the preceding
  `standardize-inventory-data-access-port-names/baseline/port-classification.tsv` artifact.
