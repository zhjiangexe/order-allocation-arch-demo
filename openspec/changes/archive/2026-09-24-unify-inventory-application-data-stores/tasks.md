## 1. Baseline and Contract Inventory

- [x] 1.1 Record all current Inventory Finder/Store declarations, methods, implementations and production callers.
- [x] 1.2 Run focused Inventory tests as the pre-change behavior baseline.

## 2. Unified Canonical-model Stores

- [x] 2.1 Merge location and operation-type Finder/Store pairs into neutral Application Stores.
- [x] 2.2 Merge operation, move and cancellation Finder/Store pairs into neutral Application Stores.
- [x] 2.3 Merge quant and move-line Finder/Store pairs into neutral Application Stores.
- [x] 2.4 Move the receipt request Store into its neutral Application port package.

## 3. Focused Planning and Projection Stores

- [x] 3.1 Rename allocation supply, candidate and backlog Finders and JDBC adapters to focused Stores.
- [x] 3.2 Rename location, operation, reconciliation and quant view Finders and adapters to focused Stores.
- [x] 3.3 Update all production composition, use-case dependencies and imports to the unified Store contracts.
- [x] 3.4 Update tests and fixtures to use the unified Store contracts without changing behavior.

## 4. Architecture and Documentation

- [x] 4.1 Remove the legacy architecture tests; defer replacement Store ownership rules to a dedicated ArchUnit change.
- [x] 4.2 Update Inventory architecture and allocation-flow documentation to explain unified Store semantics.
- [x] 4.3 Search production, tests, fixtures, SIT and living docs for retired Finder declarations and command/query port paths.

## 5. Verification

- [x] 5.1 Run `cd backend && ./gradlew spotlessApply`, `./gradlew spotlessCheck` and `git diff --check`.
- [x] 5.2 Run Inventory unit tests, monolith Inventory SIT and direct-caller compilation.
- [x] 5.3 Run the complete backend test suite and project E2E suite.
- [x] 5.4 Sync the main spec, run strict OpenSpec validation and record final Store inventory and verification results.
