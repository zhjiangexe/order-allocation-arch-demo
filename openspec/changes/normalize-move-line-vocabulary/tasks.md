## 1. Planner Proposal Vocabulary

- [x] 1.1 Replace `MoveReservationDraft` with immutable `ProposedMoveLine` and update its focused validation tests.
- [x] 1.2 Change `MovementAssignmentProposal` and planner implementations to expose `proposedMoveLines` while preserving exact-coverage and insufficient-result invariants.
- [x] 1.3 Update assignment transaction and ephemeral quant working-set consumers to commit `ProposedMoveLine` values as canonical `StockMoveLine` values without persistence or transaction changes.

## 2. Committed Projection Vocabulary

- [x] 2.1 Replace assignment-result `BatchPick`/`batchPicks` internals with `AssignedMoveLine`/`moveLines` and preserve coverage validation.
- [x] 2.2 Replace lifecycle `BatchSnapshot`/`batches` internals with `MoveLineSnapshot`/`moveLines` and preserve before-image ordering and coverage validation.
- [x] 2.3 Replace stock-operation query `BatchReservation`/`batches` internals with `MoveLineView`/`moveLines` and update JDBC projection assembly without changing SQL.

## 3. Boundary Compatibility

- [x] 3.1 Update assignment and lifecycle publication factories to translate normalized internal move-line values into the unchanged versioned `BatchPick` and `BatchSnapshot` event contracts.
- [x] 3.2 Preserve `/stock-operations` JSON `batches` through an explicit serialization alias and add controller assertions that `moveLines` is not emitted.
- [x] 3.3 Update affected unit and integration tests to assert identical quant IDs, quantities, ordering, allocation decisions, and lifecycle facts through the renamed internal models.

## 4. Verification

- [x] 4.1 Search production Inventory code for stale internal `MoveReservationDraft`, `BatchPick`, `BatchSnapshot`, and `BatchReservation` usage, allowing batch vocabulary only at compatibility boundaries or genuine receipt/response batch concepts.
- [x] 4.2 Run `backend` Palantir formatting with `./gradlew spotlessApply` and verify it with `./gradlew spotlessCheck`.
- [x] 4.3 Run the Inventory context test suites and the complete backend test suite, confirming that database schema, SQL, transaction behavior, and external contracts remain unchanged.
- [x] 4.4 Validate and verify the OpenSpec change against the completed implementation.
