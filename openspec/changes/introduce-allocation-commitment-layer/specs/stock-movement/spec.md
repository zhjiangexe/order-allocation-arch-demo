## MODIFIED Requirements

### Requirement: Recording a movement is a step of its own

A supply-only or inbound movement SHALL be recordable independently without an allocation demand or commitment. Stock-consuming source acceptance SHALL record only an allocation demand. Outbound allocation targets SHALL be recorded only by the target materializer after a commitment has created canonical allocation and slice facts.

Movement recording SHALL NOT load or mutate the source aggregate. For committed outbound work it SHALL consume immutable demand and allocation snapshots rather than route configuration or WMS state. A failed outbound transaction SHALL leave neither partial commitment nor partial movement targets.

#### Scenario: An inbound movement is recorded independently

- **GIVEN** a receipt from a supplier
- **WHEN** the receipt is accepted
- **THEN** an inbound movement can be recorded without a demand, allocation, or order id

#### Scenario: Open outbound intent is not execution

- **GIVEN** an accepted stock-consuming demand has no committed allocation
- **WHEN** its source acceptance completes
- **THEN** the demand exists and no outbound movement exists

#### Scenario: Commitment materializes outbound movement

- **GIVEN** an eligible proposal has been validated in the commitment transaction
- **WHEN** allocation and reserved slices are created
- **THEN** traceable assigned movements and lines are materialized before that transaction commits

### Requirement: An outbound target identifies its commitment and source line

Every outbound movement materialized by allocation SHALL identify one canonical allocation and exactly one allocation-demand line. It SHALL use allocation id as commitment and execution-grouping identity; demand-header trace SHALL be obtained through the allocation instead of duplicated on the movement. An inbound or supply-only movement SHALL have no allocation or allocation-demand reference.

Every outbound movement line SHALL reference exactly one allocation slice in the current single-leg route, and its stock quant and quantity SHALL equal that slice. Each reserved slice SHALL have one corresponding outbound movement line, while the slice remains authoritative if execution is regrouped. Each covered demand line SHALL have exactly one materialized outbound movement under the enabled policy.

#### Scenario: Allocation creates a traceable assigned movement

- **GIVEN** allocation `allocation-1` covers one demand line
- **WHEN** its target is materialized
- **THEN** one assigned movement references `allocation-1` and the demand line, and each movement line references its slice

#### Scenario: An inbound movement has no false allocation reference

- **WHEN** an inbound receipt movement is created
- **THEN** it has no allocation, allocation-demand, or allocation-slice reference

#### Scenario: Partial linkage is rejected

- **WHEN** an outbound allocation movement or line omits one of its required trace references
- **THEN** persistence rejects the inconsistent target

#### Scenario: Target regrouping does not change reservation

- **GIVEN** a movement line traces a reserved allocation slice
- **WHEN** execution metadata or grouping changes
- **THEN** the slice quantity and demand coverage remain unchanged

### Requirement: Picking direction does not depend on order identity

Inbound and supply-only operation semantics SHALL be determined by operation type or direction, not order identity. Outbound allocation SHALL NOT create or require an Inventory `StockPicking`; warehouse grouping and fulfillment execution SHALL be owned by WMS and keyed by canonical allocation id.

#### Scenario: Inbound picking remains operation-driven

- **WHEN** an inbound receipt creates a picking
- **THEN** its direction comes from operation configuration rather than an order id

#### Scenario: Outbound allocation has no Inventory picking

- **WHEN** an order-backed allocation commits
- **THEN** it creates slice-traceable assigned movements and no outbound `StockPicking`

#### Scenario: WMS groups work by allocation identity

- **GIVEN** a committed event carries allocation and demand ids
- **WHEN** WMS creates shipment work
- **THEN** it uses allocation id as the idempotency and grouping identity

### Requirement: Stock on hand changes only through a completed movement line

A physical stock quantity SHALL change only through a movement line naming the affected stock quant. Inbound completion SHALL increase physical quantity from its movement line without requiring allocation. Outbound completion SHALL be executed through the allocation-consumption boundary so the corresponding reserved slices, quant counters, physical quantity, and movement states change atomically.

A movement or movement line SHALL NOT reserve, release, or consume a quant independently of allocation-slice lifecycle. For outbound completion, every provided movement line SHALL reference a `RESERVED` slice with the same allocation, quant, and quantity, and the command SHALL cover the allocation's complete movement set.

#### Scenario: Stock cannot increase without an inbound line

- **GIVEN** a stock quant
- **WHEN** something attempts to increase physical quantity without an inbound movement line naming it
- **THEN** the operation is unavailable

#### Scenario: Completing inbound movement increases stock

- **GIVEN** an inbound movement with a line naming a stock quant
- **WHEN** the movement completes
- **THEN** that quant's physical quantity increases and the movement becomes done

#### Scenario: Completing outbound movement consumes its slices

- **GIVEN** a reserved allocation whose complete movement set is reported done
- **WHEN** the allocation-consumption transaction commits
- **THEN** its slices become consumed, reserved counters and physical stock decrease, and movements become done

#### Scenario: A foreign movement line is refused

- **GIVEN** a completion contains a movement line whose allocation slice belongs to another allocation
- **WHEN** outbound consumption is validated
- **THEN** no stock, slice, or movement state changes

## REMOVED Requirements

### Requirement: Reservation is a stage of a movement, not a parallel ledger

**Reason**: A movement is an execution target that may be regrouped or replaced, so its lines cannot remain the canonical demand-to-supply commitment or preserve release and reallocation history.

**Migration**: Backfill one allocation slice for every existing assigned or consumed outbound movement-line reservation, reference those slices from movement lines, and move reserve, release, and consume mutations behind the allocation commitment boundary.
