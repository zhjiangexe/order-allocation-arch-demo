## ADDED Requirements

### Requirement: Inventory remains one capability-oriented bounded context
The Inventory module SHALL remain one bounded context organized by the `movement`, `allocation`, `balance`, and `warehouse`
capabilities. It MUST NOT introduce an umbrella `stock-core`, `inventory-domain`, or equivalent package that claims ownership of all
movement, allocation, balance, and warehouse concepts.

#### Scenario: Developer locates a stock concept
- **WHEN** a developer navigates Inventory production source
- **THEN** canonical operations and moves are owned by Movement, stock quantities are owned by Balance, allocation decisions are owned
  by Allocation, and locations and operation types are owned by Warehouse

### Requirement: Domain packages are flat within their business owner
Every Inventory capability SHALL place a Domain type directly in the `domain` package of the internal business capability that owns
its rule. Production source MUST NOT classify those types into `aggregate`, `entity`, `model`, `repository`, `service`, `type`, or
`valueobject` child packages unless a future specification establishes a new independently evolving business boundary.

#### Scenario: Domain type is added or moved
- **WHEN** a Domain aggregate, value, policy, service, state, or repository is owned by an existing Inventory capability
- **THEN** its package is `inventory.<capability>.<business-capability>.domain` without an additional tactical-role segment

### Requirement: Allocation is organized by business capability before layer
Allocation SHALL organize production code under the `assignment`, `cancellation`, `intake`, and `lifecycle` business-capability
slices before applying `domain`, `application`, `entrypoint`, and `infrastructure` layers. These slices MUST remain parts of the
Inventory bounded context and MUST NOT be represented as independent bounded contexts. A slice MUST NOT contain an empty layer solely
to make its directory shape symmetrical. Allocation-wide stable subscription identities and retry observation MAY live under a
narrow `support` slice, but Domain rules and workflow orchestration MUST NOT live there.

Assignment SHALL be the core Allocation slice. Intake, Cancellation, and Lifecycle MAY depend on Assignment's Application API and
transaction working models, while Assignment MUST NOT depend on those edge slices. Intake and Lifecycle MUST NOT depend on each other
or Cancellation. Cancellation MAY additionally depend on Lifecycle's release and publication API, and Lifecycle MUST NOT depend on
Cancellation.

#### Scenario: Developer follows an assignment flow
- **WHEN** a developer navigates from an Inventory-availability trigger to assignment planning and persistence
- **THEN** the entrypoint, Application orchestration, Domain proposal and planner, outbound ports, and infrastructure adapters are all
  located below `inventory.allocation.assignment`

#### Scenario: Slice has no independent Domain model
- **WHEN** Intake or Lifecycle orchestrates canonical Movement and Balance models without owning an Allocation Domain rule
- **THEN** that slice omits an empty `domain` package

#### Scenario: Cancellation releases an existing assignment
- **WHEN** Cancellation requires the operation-to-move-to-quant working model established by Assignment
- **THEN** Cancellation depends inward on `allocation.assignment.application` without introducing a reverse Assignment dependency or
  duplicating the working model

### Requirement: Every Inventory capability is organized by business capability before layer
Balance SHALL organize code under `onhand`, `receipt`, and `visibility`; Movement SHALL organize code under `operation`,
`registration`, and `visibility`; Warehouse SHALL organize code under `location` and `operationtype`. Each capability MUST apply
`domain`, `application`, `entrypoint`, and `infrastructure` only inside those slices and MUST omit layers for which the slice owns no
code. These slices MUST remain parts of the Inventory bounded context and MUST NOT be represented as independent bounded contexts.

On-hand SHALL own the canonical Stock Quant model and persistence. Receipt and Balance Visibility MAY depend on On-hand, while
On-hand MUST NOT depend on either edge slice and Receipt and Visibility MUST NOT depend on each other. Operation SHALL own the
canonical Stock Operation, Move, Move Line, repository ports, and persistence. Registration and Movement Visibility MAY depend on
Operation, while Operation MUST NOT depend on either edge slice and Registration and Visibility MUST NOT depend on each other.
Location SHALL own the stock-location model and listing API. Operation Type MAY depend on Location, while Location MUST NOT depend on
Operation Type.

#### Scenario: Developer follows receipt through Balance
- **WHEN** a developer navigates from the receipt REST endpoint to stock mutation and availability publication
- **THEN** the use case, request port, publication port, and adapters are below `inventory.balance.receipt`, while the changed Stock
  Quant model and persistence are below `inventory.balance.onhand`

#### Scenario: Developer follows Movement registration and inspection
- **WHEN** a developer locates canonical movement creation or its diagnostic read surface
- **THEN** creation is below `inventory.movement.registration`, queries, reconciliation, health, and REST are below
  `inventory.movement.visibility`, and both depend on the canonical model below `inventory.movement.operation`

#### Scenario: Developer locates Warehouse master data
- **WHEN** a developer navigates Warehouse production source
- **THEN** stock endpoints and their listing API are below `inventory.warehouse.location`, while operation direction and operation
  type configuration are below `inventory.warehouse.operationtype`

### Requirement: Application packages express business features and explicit ports
Inventory Application code SHALL use business feature packages for workflows and SHALL place feature-owned commands, results,
projections, and working values directly in their feature package. Within Allocation, the Application layer SHALL be nested inside
the owning business-capability slice. Outbound dependency interfaces SHALL remain in an explicit `application.port` package, and
Movement-wide persistence ports SHALL be owned by `movement.application.port` without a `shared` segment.

#### Scenario: Application feature contains records and ports
- **WHEN** an Application workflow requires feature-owned values and outbound dependencies
- **THEN** the values live directly under the feature's Application package and the outbound interfaces live under its `port` child
  package

### Requirement: Architecture fitness functions remain implementation-neutral
Inventory architecture tests SHALL enforce bounded-context isolation, capability dependency direction, layer dependency rules,
framework isolation, transport isolation, port ownership, and package conventions. They MUST NOT require exact method-body text, local
property names, class modifiers, explicit production file lists, living-document wording, or names of already removed implementation
types.

#### Scenario: Internal implementation is legally refactored
- **WHEN** a class or method is renamed, split, or reorganized without changing an architectural dependency or package convention
- **THEN** architecture fitness tests continue to pass while behavioral tests determine whether runtime behavior was preserved

### Requirement: Package flattening preserves behavior and external compatibility
The package migration SHALL preserve allocation planning, assignment, cancellation, lifecycle, receipt, stock-view, and movement
registration behavior. It MUST NOT change database migrations, persisted representations, transaction boundaries, REST schemas,
integration-event schemas, external identifiers, or public runtime behavior.

#### Scenario: Flattened module is verified
- **WHEN** all package moves and architecture-test changes are complete
- **THEN** formatting, Inventory unit and architecture tests, targeted persistence/SIT tests, the full backend test suite, and project
  E2E tests pass without a database or external-contract migration
