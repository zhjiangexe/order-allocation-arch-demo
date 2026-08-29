## ADDED Requirements

### Requirement: Stock allocation planning uses explicit immutable demand and supply models

`StockAllocationPlanner` SHALL accept one immutable `StockOperationDemand` containing ordered `StockMoveDemand` rows and one immutable
`StockAllocationSupply` containing SKU-grouped `StockQuantSupply` rows. It SHALL return one immutable `StockAllocationProposal` whose
ready form contains complete `ProposedMoveLine` rows and whose insufficient form contains exact SKU shortfalls without partial rows.

`StockAllocationSupply` SHALL represent eligible planning input rather than a reservation or commitment. It SHALL preserve one owner and
source-location scope, explicit empty groups for requested SKUs, deterministic FEFO row order, and immutable quant identity, SKU, in-date,
expiry-date and available-to-promise facts. Neither the demand nor supply planning input SHALL expose mutable `StockOperation`,
`StockMove`, or `StockQuant` aggregates.

The planner SHALL remain repository-free and deterministic. A ready proposal SHALL remain non-authoritative until
`StockOperationAssignmentTransaction` reloads and locks the canonical operation, moves and selected quants and revalidates predecessor,
scope, expiry, exact coverage and available quantity.

#### Scenario: Planner receives isolated typed inputs

- **GIVEN** one confirmed stock operation and eligible FEFO quants have been projected for planning
- **WHEN** `StockAllocationPlanner` calculates an assignment
- **THEN** it receives `StockOperationDemand` and `StockAllocationSupply`
- **AND** every supply row is an immutable `StockQuantSupply` rather than a mutable `StockQuant`
- **AND** it returns a non-authoritative `StockAllocationProposal`

#### Scenario: Empty supply remains a complete query result

- **GIVEN** a demanded SKU has no eligible quant at the owner and source location
- **WHEN** the supply query constructs `StockAllocationSupply`
- **THEN** the supply contains an explicit empty group for that SKU
- **AND** the planner returns its exact shortfall without a partial `ProposedMoveLine`

#### Scenario: A ready proposal is revalidated before commitment

- **GIVEN** a ready `StockAllocationProposal` was calculated from optimistic immutable supply facts
- **WHEN** the assignment transaction applies the proposal
- **THEN** it reloads and locks the selected canonical `StockQuant` aggregates
- **AND** it rejects stale scope, expiry or ATP before changing reserved quantity or creating `StockMoveLine`

## MODIFIED Requirements

### Requirement: StockOperationAssigner is the canonical assignment application entry

Initial assignment after source registration, availability-driven retry and backlog reconciliation SHALL invoke one
`StockOperationAssigner` application façade. The façade SHALL coordinate candidate acquisition, pure planning and transactional apply;
an application use case SHALL NOT invoke another application use case to reconstruct that flow. Availability and backlog entry points
SHALL pass one transport-neutral `AssignmentQueueKey` and SHALL NOT wrap the same fields in a second command type.

`StockAllocationPlanner` SHALL remain a deterministic calculation over immutable `StockOperationDemand` and
`StockAllocationSupply` facts. `StockOperationAssignmentTransaction` SHALL remain an internal apply boundary and SHALL NOT be exposed as
an independent adapter entry point. A backlog reconciler SHALL discover retryable keys and invoke the same façade rather than duplicate
planning or apply logic.

#### Scenario: Source registration attempts assignment through the façade

- **GIVEN** source registration creates a confirmed stock operation
- **WHEN** the registration flow attempts initial assignment
- **THEN** it invokes `StockOperationAssigner` and does not invoke the assignment transaction directly

#### Scenario: Availability and reconciliation share one flow

- **GIVEN** an availability event and a scheduled reconciliation identify the same assignment queue
- **WHEN** each trigger attempts the next candidate
- **THEN** both pass the same `AssignmentQueueKey` to `StockOperationAssigner`
- **AND** both use the same candidate, planner and transactional apply sequence

### Requirement: Assignment candidate acquisition exposes immutable source facts

`AssignmentCandidateQuery` SHALL return an immutable `AssignmentCandidate` containing a `StockOperationDemand` and an optional
`StockOperationPredecessor`. It SHALL NOT expose a mutable `StockOperation` or `StockMove` aggregate to the planner or triggering adapter.
Candidate acquisition SHALL be treated as an optimistic read, and the assignment transaction SHALL lock the canonical operation and
moves, reload affected quants, and recheck exact predecessor and proposal validity before changing any target state.

#### Scenario: Planning cannot mutate the selected operation

- **GIVEN** a confirmed operation is eligible for planning
- **WHEN** `AssignmentCandidateQuery` returns its candidate
- **THEN** the candidate contains `StockOperationDemand` with immutable `StockMoveDemand` facts
- **AND** it contains no mutable operation or move aggregate

#### Scenario: A stale candidate cannot bypass final precedence

- **GIVEN** a candidate was planned before an older intersecting operation became visible to the transaction
- **WHEN** transactional apply performs its final predecessor check
- **THEN** it rejects or skips the stale proposal without creating move lines or changing reserved counters

### Requirement: Allocation command stores and read queries have separate ports

Allocation command stores SHALL expose only identity lookup, persistence and required lock operations. Candidate selection, backlog
discovery, deterministic FEFO supply and stock-operation reconciliation SHALL use purpose-specific query ports. A shared JPA aggregate
repository SHALL NOT serve as the public port for both command persistence and those unrelated read purposes.

`StockAllocationSupplyQuery` SHALL be an allocation-owned read port and SHALL return `StockAllocationSupply` by mapping eligible SQL rows
directly to immutable `StockQuantSupply` values. It SHALL NOT construct or return mutable `StockQuant` aggregates. The backlog query SHALL
expose only production reconciliation needs; an oldest-enqueued-age query with no production caller SHALL be removed rather than retained
solely for a persistence test.

#### Scenario: FEFO planning reads through an allocation supply query

- **GIVEN** a planner needs eligible quants for multiple SKUs
- **WHEN** it loads allocation supply
- **THEN** it uses `StockAllocationSupplyQuery` with deterministic owner, location, SKU and FEFO scope
- **AND** the query performs one set-based SQL read and returns immutable `StockQuantSupply` rows
- **AND** it does not call a command store's generic quant listing method

#### Scenario: Backlog discovery does not enlarge the operation store

- **WHEN** reconciliation discovers assignment queues with ready work
- **THEN** it uses `AssignmentBacklogQuery`
- **AND** `StockOperationStore` remains free of backlog and queue-head discovery methods
