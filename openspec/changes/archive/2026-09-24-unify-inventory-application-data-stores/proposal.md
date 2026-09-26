## Why

The Finder/Store split makes a technical CQRS distinction dominate the Inventory ubiquitous language and forces one
cohesive persistence capability into paired interfaces even when both are owned by the same Application workflow.
Inventory instead needs one consistent name for Application-owned data access while retaining small, purpose-specific
contracts.

## What Changes

- **BREAKING** Replace every custom Inventory `*Finder` data-access port with an Application-owned `*Store`.
- Move custom Inventory Stores from command/query-specific port packages into the owning capability's neutral
  `application.port` package.
- Allow a Store to expose reads, projections, transaction locks and mutations required by its cohesive purpose.
- Merge same-model command Finder/Store pairs into one Store instead of retaining artificial read/write twins.
- Keep focused planning, queue and visibility Stores separate so `Store` does not become a large generic Repository.
- Keep Spring Data `Jpa*Repository` interfaces as Infrastructure-only framework details.
- Preserve SQL, transaction, lock, algorithm, schema and externally observable behavior.
- Supersede the naming and package rules introduced by `separate-inventory-command-query-data-ports`; retain that
  completed change as decision history.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `inventory-data-access-port-conventions`: Standardizes all custom Inventory data-access ports as cohesive
  Application-owned Stores in neutral Application port packages, without command/query suffix classification.

## Impact

- Inventory Application ports, Infrastructure adapter contracts and names, constructor dependencies, fixtures,
  architecture tests and documentation.
- Internal Java imports and type names are breaking; REST, event, Temporal, WMS and database contracts remain
  unchanged.
