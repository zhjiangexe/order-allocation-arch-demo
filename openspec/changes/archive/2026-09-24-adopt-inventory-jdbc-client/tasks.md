## 1. Baseline and Wiring

- [x] 1.1 Run the focused Inventory test suite as the pre-migration baseline.
- [x] 1.2 Confirm Boot-managed JdbcClient wiring and retain lower-level Template usage outside the six scoped Stores.

## 2. Direct Query Stores

- [x] 2.1 Migrate allocation supply and assignment backlog Stores to injected JdbcClient.
- [x] 2.2 Migrate operation reconciliation and quant view Stores to injected JdbcClient.
- [x] 2.3 Update focused tests, format Java and run the Inventory test suite.

## 3. Complex Projection Stores

- [x] 3.1 Migrate assignment candidate Store while preserving cardinality and query sequence.
- [x] 3.2 Migrate operation view Store while preserving its hierarchical accumulator and query-count assertions.
- [x] 3.3 Format Java and rerun the Inventory test suite.

## 4. Verification

- [x] 4.1 Search production code to confirm all six scoped Stores inject JdbcClient and excluded low-level workflows retain Templates.
- [x] 4.2 Run Spotless, the complete backend test suite and `git diff --check`.
- [x] 4.3 Run monolith SIT and project E2E suites.
- [x] 4.4 Validate OpenSpec strictly and record final implementation verification.
