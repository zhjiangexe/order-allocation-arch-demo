## ADDED Requirements

### Requirement: Planning detail remains explicitly proposed

The pure stock-allocation planner SHALL express each planned move-to-quant quantity as a
`ProposedMoveLine`. A `MovementAssignmentProposal` SHALL expose those values as proposed move lines and SHALL
NOT give them a repository identity, persistence lifecycle, or authority to change a `StockMove`,
`StockMoveLine`, or `StockQuant`.

The assignment transaction SHALL remain the only boundary that may validate a ready proposal against locked
current state and create canonical `StockMoveLine` commitment detail.

#### Scenario: Ready planning produces proposed move lines without commitment

- **GIVEN** a confirmed stock operation has sufficient allocatable stock
- **WHEN** `StockAllocationPlanner` returns a ready `MovementAssignmentProposal`
- **THEN** its detail is exposed as immutable `ProposedMoveLine` values
- **AND** no `StockMoveLine` has been persisted and no `StockQuant` counter has changed

#### Scenario: Transaction converts valid proposed detail into canonical detail

- **GIVEN** a ready proposal still matches the locked operation, moves, quants, and available quantities
- **WHEN** the assignment transaction commits the proposal
- **THEN** it persists canonical `StockMoveLine` values with the same move, quant, and quantity relationships
- **AND** it does not persist a separate proposal or draft model

### Requirement: Internal reservation projections retain StockMoveLine lineage

Internal representations derived from committed `StockMoveLine` values SHALL use stage-qualified MoveLine
vocabulary. The committed assignment result SHALL expose `AssignedMoveLine` values, the lifecycle before-image
SHALL expose `MoveLineSnapshot` values, and the stock-operation query projection SHALL expose `MoveLineView`
values.

These projections SHALL preserve the existing move ID, stock-quant ID, quantity, validation, coverage, and
ordering semantics. They SHALL NOT represent WMS physical picking or introduce a second reservation aggregate.

#### Scenario: Assignment result reconstructs assigned move lines

- **GIVEN** an assigned stock operation has committed move lines
- **WHEN** its canonical assignment result is reconstructed
- **THEN** each assigned move exposes its committed detail as `AssignedMoveLine` values
- **AND** the values exactly cover the assigned move quantity

#### Scenario: Lifecycle processing captures move-line before-image

- **GIVEN** lifecycle processing is about to remove committed reservation detail
- **WHEN** it captures the operation before-image
- **THEN** each move snapshot exposes that detail as `MoveLineSnapshot` values
- **AND** the captured quant IDs and quantities equal the committed move lines

#### Scenario: Query projection exposes current move-line view

- **GIVEN** a stock operation has current committed reservation detail
- **WHEN** the stock-operation query assembles its read model
- **THEN** each move exposes the joined detail as `MoveLineView` values
- **AND** each view retains its stock-quant identity and existing quant attributes

### Requirement: Vocabulary normalization preserves external contracts and behavior

Normalizing internal MoveLine vocabulary SHALL NOT change integration-event type names, versions, channels,
serialized payload fields, REST JSON fields, database schema, SQL behavior, transaction boundaries, lock order,
allocation decisions, or query count.

Publication boundaries SHALL continue mapping normalized internal values to the existing versioned
`BatchPick` and `BatchSnapshot` contract values. The `/stock-operations` response SHALL continue serializing
move detail under the `batches` property and SHALL NOT expose a new `moveLines` JSON property in the existing
contract.

#### Scenario: Assignment publication keeps its versioned batch-pick contract

- **GIVEN** an assignment result contains internal `AssignedMoveLine` values
- **WHEN** the order-assignment integration event is published
- **THEN** the existing event version and `batchPicks` payload shape are unchanged
- **AND** every published stock-quant ID and quantity equals the corresponding internal assigned move line

#### Scenario: Lifecycle publication keeps its versioned batch-snapshot contract

- **GIVEN** a lifecycle before-image contains internal `MoveLineSnapshot` values
- **WHEN** the lifecycle integration event is published
- **THEN** the existing event version and `batches` payload shape are unchanged
- **AND** every published stock-quant ID and quantity equals the corresponding internal move-line snapshot

#### Scenario: Stock-operation REST response keeps its existing JSON property

- **GIVEN** a stock-operation query view contains internal `MoveLineView` values
- **WHEN** the view is serialized by the existing REST endpoint
- **THEN** move detail is present under `moves[*].batches`
- **AND** `moves[*].moveLines` is absent
