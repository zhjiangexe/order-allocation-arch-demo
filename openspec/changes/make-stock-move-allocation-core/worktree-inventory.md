# Worktree inventory before move-centric cutover

Captured on 2026-08-27 before implementation. This inventory is the deletion and preservation boundary for
`make-stock-move-allocation-core`; it is not evidence that any dirty file may be reset wholesale.

## Classification rule

- **Superseded-only** means the file exists solely to implement `AllocationDemand` as the pending requirement or
  `Allocation`/`AllocationSlice` as a second reservation ledger. These files may be removed or replaced once their call sites have
  moved to `StockPicking`/`StockMove`.
- **Overlapping** means a tracked file contains useful cross-context or lifecycle work as well as a superseded identity. It must be
  edited forward; restoring the entire file would discard reusable behavior.
- **Preserve** means the file is either the active OpenSpec change, an architectural source document to be rewritten, or an
  unrelated/user-owned artifact. It must not be removed as cleanup for the superseded design.

## Superseded-only files

### Unpublished Flyway drafts

All nine worktree-only migrations below belong exclusively to `introduce-allocation-commitment-layer` and are replaced before
publication:

- `backend/deployments/monolith/src/main/resources/db/migration/V21__expand_allocation_commitment_layer.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V22__backfill_allocation_commitment_facts.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V23__add_wms_allocation_demand_trace.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V24__make_allocation_demand_lifecycle_source_only.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V25__key_allocation_cancellation_by_allocation.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V26__scope_movement_target_uniqueness_to_allocation.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V27__validate_allocation_commitment_cutover.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V28__enforce_allocation_target_references.sql`
- `backend/deployments/monolith/src/main/resources/db/migration/V29__remove_stock_move_demand_header_trace.sql`

`V12`, `V13`, and `V15` are not drafts. They are published, tracked migrations whose dirty hunks must be restored exactly to Git
content. No other V1–V20 migration is dirty.

### Allocation commitment production slice

The following worktree-only files are a duplicate commitment ledger and are superseded in full:

- `inventory/allocation/domain/aggregate/Allocation.java`
- `inventory/allocation/domain/entity/AllocationSlice.java`
- `inventory/allocation/domain/repository/AllocationCoverageRepository.java`
- `inventory/allocation/domain/repository/AllocationRepository.java`
- `inventory/allocation/domain/service/ShipCompleteAllocationPolicy.java`
- `inventory/allocation/domain/type/AllocationLifecycle.java`
- `inventory/allocation/domain/type/AllocationPolicyCode.java`
- `inventory/allocation/domain/type/AllocationSliceState.java`
- `inventory/allocation/domain/valueobject/AllocationBlocker.java`
- `inventory/allocation/domain/valueobject/AllocationDemandLineCoverage.java`
- `inventory/allocation/domain/valueobject/AllocationId.java`
- `inventory/allocation/domain/valueobject/AllocationPlanningSnapshot.java`
- `inventory/allocation/domain/valueobject/AllocationProposal.java`
- `inventory/allocation/domain/valueobject/AllocationSliceConsumption.java`
- `inventory/allocation/domain/valueobject/AllocationSliceDraft.java`
- `inventory/allocation/domain/valueobject/AllocationSliceId.java`
- `inventory/allocation/application/command/ConsumeAllocationCommand.java`
- `inventory/allocation/application/result/ConsumeAllocationResult.java`
- `inventory/allocation/application/service/commitment/AllocationCommitment.java`
- `inventory/allocation/application/service/commitment/AllocationCommitmentWriter.java`
- `inventory/allocation/application/service/commitment/AllocationExecutionTarget.java`
- `inventory/allocation/application/service/commitment/AllocationTargetMaterializer.java`
- `inventory/allocation/application/usecase/CommitAllocationUsecase.java`
- `inventory/allocation/application/usecase/ConsumeAllocationUsecase.java`
- `inventory/allocation/application/usecase/ReleaseAllocationUsecase.java`
- `inventory/allocation/infrastructure/entity/AllocationEntity.java`
- `inventory/allocation/infrastructure/entity/AllocationSliceEntity.java`
- `inventory/allocation/infrastructure/mapper/AllocationMapper.java`
- `inventory/allocation/infrastructure/repository/AllocationCoverageRepositoryImpl.java`
- `inventory/allocation/infrastructure/repository/AllocationRepositoryImpl.java`
- `inventory/allocation/infrastructure/repository/jpa/JpaAllocationRepository.java`
- `inventory/allocation/infrastructure/repository/jpa/JpaAllocationSliceRepository.java`

Paths above are relative to `backend/inventory-context/src/main/java/com/flowzati/archone/`. The associated worktree-only tests are
also superseded:

- `backend/inventory-context/src/test/java/com/flowzati/archone/inventory/allocation/application/usecase/ConsumeAllocationUsecaseTest.java`
- `backend/inventory-context/src/test/java/com/flowzati/archone/inventory/allocation/application/usecase/ReleaseAllocationUsecaseTest.java`
- `backend/inventory-context/src/test/java/com/flowzati/archone/inventory/allocation/domain/aggregate/AllocationTest.java`
- every worktree-only test under `inventory/allocation/domain/valueobject/` named `AllocationBlockerTest`,
  `AllocationDemandLineCoverageTest`, `AllocationIdentityTest`, or `AllocationProposalTest`
- `backend/deployments/monolith/src/sit/java/com/flowzati/archone/inventory/allocation/application/usecase/AllocationCommitmentRollbackIntegrationTest.java`
- `backend/deployments/monolith/src/sit/java/com/flowzati/archone/inventory/allocation/application/usecase/AllocationReleaseConsumeConcurrencyIntegrationTest.java`
- `backend/deployments/monolith/src/sit/java/com/flowzati/archone/inventory/allocation/entrypoint/messaging/AllocationCommitmentRaceIntegrationTest.java`
- `backend/deployments/monolith/src/sit/java/com/flowzati/archone/inventory/allocation/infrastructure/repository/AllocationCommitmentPersistenceIntegrationTest.java`

The worktree-only commitment reconciliation and lifecycle-observation files are not retained as APIs. Their useful invariants and
metrics are rewritten against moves and move lines:

- `inventory/allocation/application/service/observability/AllocationLifecycleObservation.java`
- `inventory/allocation/application/service/observability/AllocationLifecycleObserver.java`
- `inventory/allocation/infrastructure/health/AllocationCommitmentReconciliationRepository.java`
- `inventory/allocation/infrastructure/observability/MicrometerAllocationLifecycleObserver.java`
- their worktree-only tests under matching `infrastructure/health` and `infrastructure/observability` packages

### Superseded OpenSpec artifacts

The complete directories below document intermediate models and remain reference-only while this change is implemented. They are
not production input and are not to be archived as the final architecture:

- `openspec/changes/separate-allocation-source-process-target/`
- `openspec/changes/introduce-allocation-commitment-layer/`

## Overlapping tracked files: edit forward, never reset wholesale

Every dirty tracked Java, contract fixture, E2E feature and documentation file outside the superseded-only list is overlapping. The
important groups are:

- `backend/inventory-context/.../inventory/allocation/**`: replace demand selection, planning, commit, cancellation, REST, health
  and persistence APIs with picking/move equivalents; do not preserve demand identities merely because the file is tracked.
- `backend/inventory-context/.../inventory/movement/**`: retain the existing movement lifecycle and repository foundations while
  removing `allocationId`, `allocationDemandLineId`, `orderLineId`, and `allocationSliceId` core references.
- `backend/inventory-context/.../inventory/balance/**`: retain physical stock and quant counter behavior; redirect reservation,
  release, and completion to canonical move lines.
- `backend/integration-contracts/**`, `backend/ordering-context/**`, `backend/wms-context/**`,
  `backend/fulfillment-temporal-contract/**`, `backend/fulfillment-temporal-runtime/**`, and bootstrap wiring: preserve the useful
  completion/cancellation/Temporal work, but introduce tolerant legacy/new readers and change the canonical new identity to
  `pickingId`.
- `backend/deployments/monolith/src/{test,sit}/**` and `e2e/spec/features/**`: rewrite assertions and fixtures; these tests are
  behavioral evidence, not cleanup targets.
- dirty tracked `docs/**` and `openspec/specs/**`: rewrite or later sync; never delete as implementation cleanup.

Tracked deletions shown by Git are also overlapping. In particular the old committer/canceller/value-object deletions and the moved
outbound-completion subscribers must be reconciled with the move-centric replacement before their final deletion is accepted.

## Preserve boundary

- `openspec/changes/make-stock-move-allocation-core/**` is the controlling artifact set.
- `docs/architecture/allocation-precedence-policy.md` and the pending-demand diagram artifacts are worktree-only outputs from the
  intermediate design. Their content will be replaced by move-centric documentation; they are not evidence for commitment-layer
  persistence.
- No dirty path outside the repository-reported status is in scope, and no unrelated user change may be reset to obtain a clean
  tree.

## Verification snapshot

- `git diff --name-only` reports historical migration changes only in V12, V13, and V15 for V1–V20.
- `git ls-files --others --exclude-standard` reports V21–V29 as untracked; Git has no published checksum for them.
- The implementation proceeds by explicit patches and focused tests. It must not use `git reset`, `git checkout --`, or blanket
  directory replacement.
