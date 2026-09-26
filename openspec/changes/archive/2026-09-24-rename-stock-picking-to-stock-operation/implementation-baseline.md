# Implementation baseline

Recorded on 2026-08-27 (Asia/Taipei) before the stock-operation rename.

## Move-centric specification baseline

- `make-stock-move-allocation-core` is complete at 54/54 tasks.
- Its delta specs were synchronized without archiving the change.
- The retired empty `openspec/specs/allocation-demand/spec.md` was removed.
- `stock-allocation`, `stock-movement`, and `rename-stock-picking-to-stock-operation` pass strict OpenSpec validation.
- Existing unrelated strict-validation failures in `fifo-replenishment-demo`, `order-intake`, and
  `outbox-event-delivery` are outside this change.

## Characterization commands and results

| Scope | Command | Result |
| --- | --- | --- |
| Integration contracts, workflow contracts/runtime, Inventory, WMS, monolith unit and architecture tests | `cd backend && ./gradlew :integration-contracts:test :fulfillment-temporal-contract:test :fulfillment-temporal-runtime:test :inventory-context:test :wms-context:test :deployments:monolith:test --rerun-tasks` | PASS; 78 Gradle tasks executed |
| PostgreSQL migrations, persistence, concurrency, FIFO/FEFO, Outbox, Inventory/WMS event chain | `cd backend && ./gradlew :deployments:monolith:sit --rerun-tasks` | PASS; 71 Gradle tasks executed |
| Events and Temporal order-to-allocation-to-WMS flows | `make e2e` | PASS; all Karate suites passed |

The initial non-rerun Gradle command was intentionally not accepted as evidence because all tasks were
`UP-TO-DATE`.

## Rename allowlist

The pre-rename search matched the following number of files:

| Scope | Files matching `StockPicking`, Inventory `Picking*`, `pickingId`, `picking_id`, or `stock_pickings` | Treatment |
| --- | ---: | --- |
| Inventory production | 63 | Canonical rename target |
| WMS production | 11 | Rename Inventory correlation fields to `stockOperationId`; preserve genuine `PickTask`, `pickingWork`, pick and short-pick terms |
| Fulfillment workflow contract/runtime | 9 | Canonical DTO/signal rename target; preserve the explicit legacy Temporal signal/DTO compatibility reader |
| Integration contracts | 7 | Add canonical contract versions; retain legacy versions as boundary readers |
| Monolith production/bootstrap | 15 | Rename canonical wiring, seed and current query vocabulary; preserve explicit compatibility adapters |
| Monolith SIT | 17 | Rename current assertions; preserve upgrade/replay fixtures that intentionally exercise legacy data |
| Living docs | 13 | Rename Inventory operation vocabulary; preserve WMS picking and historical/archived documents |
| Current OpenSpec main specs | 2 | Rename current Inventory operation vocabulary |

Published V1-V29 Flyway files and archived/completed OpenSpec artifacts are historical records and are not
mechanically rewritten. Current source, V30 schema mappings, active contracts, active tests, and living docs are the
bounded completion scope.

## Compatibility retention inventory

Stable logical channels currently include:

- `ordering.order-events`
- `promising.allocation-events`
- `promising.fulfillment-handoffs`
- `inventory.stock-events`
- `inventory.stock-operation-events`
- `wms.shipment-events`

The Kafka runtime publishes a failed record to `<physical-topic>-dlt` and preserves original topic, partition,
offset, timestamp, subscriber, message identity, event type, and headers for replay.

| Environment/source | Topic and DLT retention | Outbox retention | Temporal history retention / maximum workflow duration | Cleanup implication |
| --- | --- | --- | --- | --- |
| Repository dev compose | No explicit Kafka retention override; broker default applies | No automatic cutoff is configured; cleanup is a manual runbook operation | Namespace is `default`; the dev server and application define no explicit namespace retention or workflow run/execution timeout. Activities use a 30-second start-to-close timeout. | Legacy readers cannot be removed from repository defaults using a known elapsed-time threshold |
| Repository stage compose | No explicit override committed | No automatic cutoff committed | No explicit value committed | Deployment owner must supply measured/configured values before cleanup |
| Repository prod compose | No explicit override committed | No automatic cutoff committed | No explicit value committed | Deployment owner must supply measured/configured values before cleanup |
| Isolated e2e | Ephemeral broker and volumes are destroyed after the suite | Ephemeral PostgreSQL data is destroyed after the suite | Ephemeral Temporal data is destroyed after the suite | Provides compatibility behavior evidence, not a production removal window |

Therefore this change deploys tolerant legacy/new readers and a single canonical producer, but it does not remove
legacy event contracts, aggregate-type normalization, or the `pickingAssigned` Temporal handler. A later cleanup
change must first record the real environment values, verify that topic/DLT/Outbox replay windows have elapsed, and
verify that no in-flight workflow history still requires the legacy signal.
