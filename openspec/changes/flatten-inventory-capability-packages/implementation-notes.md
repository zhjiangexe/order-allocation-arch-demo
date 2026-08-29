# Implementation inventory

## Scoped source surface

- The retired Domain taxonomy, feature-local Application `model`, and movement `shared/port` packages currently contain 64 Java
  sources across `main`, `test`, and `testFixtures`.
- A repository-wide import and text scan found 190 Java, SIT, fixture, monolith, WMS, documentation, and OpenSpec files referencing at
  least one old package. Every replacement is limited to a package declaration, import, fully qualified type, or source-path assertion.
- Three AspectJ expressions originally targeted `allocation.application.assignment`; the second migration moves that slice and updates
  every expression to `allocation.assignment.application` so retry/concurrency advice remains active.
- The only reflection-sensitive code found is generic controller discovery in `ContextBoundaryArchitectureTest`; it does not name a
  moving Inventory type.

The mechanical package map is:

```text
allocation/domain/{aggregate,repository,service,type,valueobject} -> allocation/domain
balance/domain/{aggregate,repository,service}                     -> balance/domain
movement/domain/model/{operation,move}                             -> movement/domain
warehouse/domain/{aggregate,repository,type}                       -> warehouse/domain
application/<feature>/model                                       -> application/<feature>
movement/application/shared/port                                  -> movement/application/port
```

## Behavior baseline

Before package movement, `./gradlew :inventory-context:test` passes. Representative persistence/query SIT tests for Stock Operation
registration, allocation assignment, Stock Quant, and Stock Operation view also pass.

## Dirty worktree boundary

The worktree already contains intentional Inventory redesign work, including untracked database migrations V21 through V30 and
tracked/untracked integration-contract V1 through V3 changes. They predate this change and are explicitly outside its scope. This
change will not edit migration SQL, integration-contract source/fixtures, messaging modules, REST field definitions, entity column
mappings, or persisted enum values. Unrelated modified and untracked files must not be reset, deleted, or reformatted.

## Implemented structure

- Movement, Balance, and Warehouse production Domain types live directly in their capability `domain` package. Allocation Domain types
  live directly in the `assignment/domain` or `cancellation/domain` business owner.
- Allocation Application values live directly in `assignment/application`, `cancellation/application`, `intake/application`, or
  `lifecycle/application`; outbound interfaces remain below each slice's `application/port`.
- `movement/application/shared/port` is now `movement/application/port`.
- `InventoryBoundaryArchitectureTest` now contains only generic source-level table/package convention checks. Compiled context, layer,
  framework, transport, and capability dependency rules use the monolith's existing ArchUnit dependency; no new library was added.
- Package moves and replacements were mechanical. No production method body, SQL, entity mapping, event mapping, or transaction
  annotation was intentionally changed. All retired package declarations/imports are absent from current production, test, fixture,
  SIT, and living documentation source.

After the move, Inventory unit/architecture tests and monolith context architecture tests pass. `spotlessApply`, `spotlessCheck`, and
`git diff --check` also pass.

The complete Inventory monolith SIT selection passes 110 tests across 28 suites with zero failures, errors, or skips.

The complete backend `test` task passes after the package migration.

The complete Karate E2E suite passes all 17 scenarios. This covers catalog/idempotency, Events fulfillment and cancellation,
connector catch-up, in-flight shipment cancellation, Temporal fulfillment and cancellation, receipt-driven reassignment, allocation,
and stock-view projections.

Final verification also passes `openspec validate flatten-inventory-capability-packages --strict`, `spotlessCheck`, and
`git diff --check`. No retired package reference remains under `backend` or living `docs`, every generated Karate JUnit suite reports
zero failures, and the isolated E2E containers were cleaned up. The change remains a source/package and architecture-test refactor:
no database migration, persisted representation, REST or integration-event schema, external identifier, SQL, transaction boundary,
or business algorithm was added or changed by this package-flattening work.

## Allocation vertical-slice amendment

The second package migration replaces Allocation's mixed layer-first/workflow-first organization with:

```text
allocation/
  assignment/{domain,application,entrypoint,infrastructure}
  cancellation/{domain,application,entrypoint,infrastructure}
  intake/{application,entrypoint,infrastructure}
  lifecycle/{application,entrypoint,infrastructure}
  support/{entrypoint,infrastructure}
```

`support` contains only stable subscriber identities and cross-slice optimistic-lock retry observation. No Domain or Application
business behavior is placed there. Intake and Lifecycle omit empty Domain packages.

Focused architecture validation exposed that `StockOperationComposite`, `MoveQuantAllocationSet`, and
`StockOperationLifecycleSnapshot` are not Lifecycle-owned values: Assignment commit creates and validates them, while Cancellation
and Lifecycle reuse them to release or consume an existing reservation. They therefore live in the core Assignment Application slice.
The enforced acyclic dependency graph is Assignment inward; Intake and Lifecycle may depend on Assignment; Cancellation may depend on
Assignment and Lifecycle; Assignment never depends back on those edge slices.

All production, unit, fixture, monolith SIT, AspectJ, WMS, bootstrap, and living-document references now use the slice-first packages.
The former `allocation/{domain,application,entrypoint,infrastructure}` production trees are absent. Inventory unit tests, the generic
Inventory source-boundary tests, monolith ArchUnit context rules, messaging architecture tests, and the complete Inventory monolith
SIT selection pass after the amendment.

## Complete Inventory vertical-slice amendment

The third package migration applies the same package grammar to the remaining Inventory capabilities without copying Allocation's
slice names or creating empty layers:

```text
balance/
  onhand/{domain,infrastructure}
  receipt/{application,entrypoint,infrastructure}
  visibility/{application,entrypoint,infrastructure}

movement/
  operation/{domain,application,infrastructure}
  registration/{application}
  visibility/{application,entrypoint,infrastructure}

warehouse/
  location/{domain,application,entrypoint,infrastructure}
  operationtype/{domain,infrastructure}
```

On-hand owns `StockQuant` and its persistence. Operation owns `StockOperation`, `StockMove`, `StockMoveLine`, their persistence ports,
and their adapters. Location owns stock endpoints and the listing API. Receipt, Registration, and both Visibility slices remain edge
workflows that depend on their core rather than becoming duplicate models. Operation Type may use Location, while Location remains
independent.

The cross-capability `InboundEntrypointTransactionIntegrationTest` now sits at the Inventory SIT root instead of pretending to be a
Balance use case. `OutboundFulfillmentEventChainIntegrationTest` now sits with Allocation Lifecycle, which owns the handover event
flow it verifies. All other unit, fixture, and SIT packages mirror their production owner.

Production, test, fixture, SIT, monolith, WMS, AspectJ/source-scan, and living-document references use the slice-first packages. No
retired Balance, Movement, or Warehouse layer-first FQCN or source path remains under `backend` or living `docs`, and their production
trees contain no empty retired directory. Focused source-boundary, ArchUnit context, and pure-messaging architecture tests pass after
the migration.

Palantir formatting, `spotlessCheck`, `git diff --check`, all Inventory unit and architecture tests, and the complete Inventory
monolith SIT selection pass after the third migration. The SIT selection remains 110 tests with zero failures.

The complete backend `test` task and complete isolated Karate E2E suite also pass. Karate reports 17 scenarios across Events,
Temporal, receipt, allocation, cancellation, connector catch-up, and stock visibility with zero failures; its isolated containers are
cleaned up.

Final strict OpenSpec validation, `spotlessCheck`, and `git diff --check` pass. The third migration changed only package declarations,
imports, source locations, package-sensitive architecture checks, and living-document links. It introduced no database migration,
entity mapping, SQL, REST or integration-event schema, transaction boundary, external identifier, or business-algorithm change.
