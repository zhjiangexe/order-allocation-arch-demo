## 1. Inventory Planner Boundary

- [x] 1.1 Add the Inventory-specific `StockAllocationPlanner` interface using the existing movement demand-group, allocatable supply, and proposal types.
- [x] 1.2 Make `MovementAssignmentPlanner` implement the port without changing SHIP_COMPLETE or FEFO behavior.

## 2. Application Wiring

- [x] 2.1 Change `StockOperationAssigner` to depend on `StockAllocationPlanner` instead of the concrete planner.
- [x] 2.2 Update assignment orchestration tests to mock the port and preserve query-plan-commit assertions.

## 3. Verification

- [x] 3.1 Run Palantir Java formatting and `spotlessCheck` for the backend.
- [x] 3.2 Run the Inventory context test suite, including existing planner and POC characterization tests.
- [x] 3.3 Validate the OpenSpec change and confirm production contains no generic Inventory/WMS planner parent.
