## Why

Inventory read Stores currently split ordinary query code between `JdbcTemplate` and `NamedParameterJdbcTemplate`
even though Spring Boot 4 already provides one auto-configured `JdbcClient` facade. Adopting that facade for common
query/update operations gives the adapters one fluent API without changing SQL or pretending that advanced JDBC
workflows no longer need the lower-level templates.

## What Changes

- Inject the Spring Boot-managed `JdbcClient` into the six Inventory JDBC read/planning Stores.
- Preserve every SQL statement, parameter value, row ordering, result cardinality and projection mapping.
- Migrate in small batches and run focused Inventory tests after each batch.
- Keep `JdbcTemplate` for migration/SIT fixtures, explicit connection control, batch work and other low-level JDBC
  operations.
- Update adapter unit tests to wrap their counting `JdbcTemplate` with `JdbcClient` instead of weakening query-count
  assertions.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `inventory-data-access-port-conventions`: Define when Inventory Infrastructure uses the auto-configured `JdbcClient`
  and when direct Template access remains appropriate.

## Impact

- Inventory Infrastructure JDBC Store constructors and query syntax.
- Two focused adapter unit tests and affected Spring context wiring.
- No database schema, SQL semantics, transaction boundary, locking behavior, Application port or external contract
  changes.
