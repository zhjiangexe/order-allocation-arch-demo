# Implementation Verification: adopt-inventory-jdbc-client

Verified on 2026-08-29.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 12/12 tasks complete; 3/3 requirements implemented |
| Correctness | 6/6 specified scenarios covered by implementation and verification |
| Coherence | Implementation follows all four design decisions |

## Completeness

- All six scoped production Stores inject the Boot-managed `JdbcClient`:
  - `JdbcStockAllocationSupplyStore.java:34`
  - `JdbcStockOperationAssignmentBacklogStore.java:14`
  - `JdbcStockOperationAssignmentCandidateStore.java:28`
  - `JdbcStockOperationReconciliationStore.java:13`
  - `JdbcStockOperationViewStore.java:69`
  - `JdbcStockQuantViewStore.java:22`
- Direct `JdbcTemplate` usage remains limited to integration/migration fixtures, explicit connection helpers and
  counting test delegates; it was not mechanically replaced.
- No custom project-owned `JdbcClient` bean was added.

## Correctness

### Requirement: Common Inventory JDBC operations use the managed JdbcClient

- Named scalar and collection binding is implemented in `JdbcStockAllocationSupplyStore.java:50`.
- Positional mapped queries are implemented in `JdbcStockOperationAssignmentBacklogStore.java:25` and
  `JdbcStockQuantViewStore.java:30`.
- Zero-or-one cardinality is preserved through `optional()` in
  `JdbcStockOperationAssignmentCandidateStore.java:73`.
- Hierarchical rows still enter the existing accumulator through one statement in
  `JdbcStockOperationViewStore.java:115`; the query-count contract remains asserted in
  `JdbcStockOperationViewStoreTest.java:16`.

### Requirement: Low-level JDBC workflows retain direct Template access

- Explicit connection, migration, database seeding and SIT fixtures continue using `JdbcTemplate` and
  `SingleConnectionDataSource` where applicable.
- Counting Template delegates are wrapped with `JdbcClient.create(...)` in
  `JdbcStockOperationViewStoreTest.java:18` and `JdbcStockQuantViewStoreTest.java:21`.

### Requirement: JdbcClient migration preserves persistence behavior

- Existing SQL text, bind order, ordering clauses and row mapping remain in the six Store adapters.
- Inventory unit tests, complete backend tests, PostgreSQL/transaction SIT and both events/Temporal E2E modes pass.
- The verification run also found and repaired stale AOP pointcuts left by the earlier package flattening. The
  production retry translator and two concurrency-test conflict injectors now target the current coordinator and
  committer packages; this restores the intended optimistic-lock retry behavior without changing allocation policy.

## Coherence

- Stores receive `JdbcClient` through constructor injection; they do not call `JdbcClient.create(...)` in production.
- Migration was completed in the two designed batches, with Inventory tests after each batch.
- The candidate and operation-view accumulators were retained instead of being replaced with lossy generic mapping.
- Lower-level Template usage remains an intentional boundary rather than an incomplete repository-wide migration.

## Verification Evidence

- `./gradlew :inventory-context:test`: passed, 144 tests.
- `./gradlew spotlessCheck test`: passed.
- `./gradlew :deployments:monolith:sit`: passed, 186 tests.
- `make e2e`: passed, 7 feature suites and 17 scenarios.
- `git diff --check`: passed.
- `openspec validate adopt-inventory-jdbc-client --strict`: passed. The CLI emitted only a non-fatal telemetry
  network warning after validation.

## Issues

- CRITICAL: none.
- WARNING: none.
- SUGGESTION: none.

All checks passed. Ready for archive.
