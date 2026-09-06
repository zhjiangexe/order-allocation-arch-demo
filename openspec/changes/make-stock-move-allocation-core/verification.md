# Verification record

Date: 2026-08-27<br>
Host environment: macOS, Java 25, Gradle 9.5.1, Docker Compose isolated E2E stack<br>
Database/infrastructure: PostgreSQL, Kafka, Debezium Connect and Temporal

## Executed suites

| Command | Scope | Result |
| --- | --- | --- |
| `cd backend && ./gradlew test` | Complete backend unit and architecture suite | Passed; 135 Gradle tasks |
| `cd backend && ./gradlew :deployments:monolith:sit` | Complete PostgreSQL migration/persistence/concurrency/Outbox SIT | Passed; 183 tests |
| `make e2e` | Isolated project E2E: events, connector catch-up, delayed WMS cancellation and Temporal | Passed; 7 features / 17 scenarios |
| `cd backend && ./gradlew :inventory-context:test --tests '*CancelPickingUsecaseTest' --tests '*AllocationIntegrationEventConsumersTest' :deployments:monolith:sit --tests '*AllocationWorkflowEndToEndIntegrationTest'` | Focused trusted-terminal cancellation regression | Passed |

The E2E runner built the executable monolith JAR, started fresh PostgreSQL/Kafka/Debezium Connect/Temporal containers, registered the
Outbox connector, exercised events and Temporal profiles, and removed the isolated stack after completion. No suite listed above is an
inferred or unexecuted success.

## OpenSpec implementation audit

The delta contains 26 requirement changes and 47 scenarios. Every active requirement is mapped to production code and executable
evidence; every removed requirement is covered by source/schema absence checks or migration contraction tests.

| Concern | Production evidence | Executable evidence |
| --- | --- | --- |
| Source registration and canonical movement identity | `StockMovementGroupRegistrar`, `StockPicking`, `StockMove` | `StockMovementGroupRegistrarTest`, `StockMovementSchemaIntegrationTest` |
| Pure planning and exact shared-SKU precedence | `MovementAssignmentPlanner`, `PendingPickingSelectionImpl` | `MovementAssignmentPlannerTest`, `PendingPickingSelectionImplTest`, `PendingPickingSelectionPersistenceIntegrationTest` |
| Atomic assignment and deterministic locks | `AssignPickingUsecase`, move-id ordered repository locks, globally ordered quant locks | `AssignPickingUsecaseTest`, `AllocationWorkflowEndToEndIntegrationTest`, concurrency SIT |
| Release, cancellation and completion | `ReleasePickingUsecase`, `PickingCancellationTransactions`, `CompletePickingUsecase` | focused lifecycle unit tests and PostgreSQL workflow SIT |
| V2 boundary and retained V1 replay | move-centric contracts, WMS/Temporal tolerant consumers, `MoveBackedLegacyAllocationStockOperationResolver` | JSON contract tests and focused WMS/Temporal compatibility tests |
| Migration and removal of parallel persistence | V21–V29 expand/backfill/validate/contract chain | `MigrationChecksumGuardTest`, `DatabaseFoundationIntegrationTest`, schema SIT and architecture tests |
| Runtime convergence and cross-context ownership | backlog scheduler, lifecycle Outbox facts, WMS shipment keyed by `pickingId` | complete backend suite and 17-scenario Events/Temporal E2E |

Verification found and resolved these mismatches before review:

1. Assigned-order cancellation now carries a durable `WarehouseCancellationCheckpoint`; a trusted WMS terminal fact is not rechecked
   through the fail-closed direct-command adapter.
2. Picking documentation and Javadocs now describe an Inventory movement-policy group, never WMS task truth.
3. Locked move loading now follows the specified `moveId` ascending order rather than presentation `lineSequence` order.
4. A retained V1 allocation event resolves its canonical `pickingId` through its existing `moveId` values. Legacy `allocationId` is
   never reinterpreted as picking identity and no demand/allocation persistence was restored.
5. Temporal architecture documentation now uses `pickingId` as the WMS idempotency key and `pickingAssigned` as the workflow fact.

Strict OpenSpec validation, `spotlessCheck`, `git diff --check`, focused compatibility tests and the complete backend test suite pass on
the final source. After the audit fixes, the complete PostgreSQL SIT was rerun successfully in 3m24s with 183 tests, followed by a fresh
isolated `make e2e` run in which all 7 features and 17 scenarios passed.
