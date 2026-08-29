## Why

Inventory currently uses `Repository` for both Domain aggregate collections and Application-owned command/query ports,
so a type name alone does not reveal its ownership or CQRS role. A small, enforced vocabulary will make package
placement and dependency review mechanical without introducing many overlapping suffixes.

## What Changes

- Reserve the `Repository` suffix for Inventory Domain aggregate collection interfaces.
- Rename Application command-side mutable persistence ports to the `Store` suffix.
- Rename Application read-only access ports to the `Finder` suffix, including read projections used while handling a
  command.
- Rename matching adapters, injected fields and callers with the full port name while leaving Spring Data
  `Jpa*Repository` infrastructure types unchanged.
- Add architecture fitness functions for the suffix/package rules and for Finder read-only behavior.
- Preserve all queries, writes, locking, transaction boundaries, database mappings and public contracts.

## Capabilities

### New Capabilities

- `inventory-data-access-port-conventions`: Defines the Inventory `Repository`/`Store`/`Finder` ownership and CQRS
  naming rules.

### Modified Capabilities

None. This is an internal architecture and naming change with no externally observable behavior change.

## Impact

- Inventory Domain and Application port interfaces, infrastructure adapter class names, constructor injection fields,
  tests, architecture rules and architecture documentation.
- No REST/event/Temporal/WMS contract, schema, migration, SQL semantics, algorithm, transaction or lock-order change.
- Java source compatibility changes for internal callers importing renamed Inventory ports/adapters.
