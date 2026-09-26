## ADDED Requirements

### Requirement: Common Inventory JDBC operations use the managed JdbcClient
Inventory JDBC Store adapters SHALL use the Spring Boot-managed `JdbcClient` for ordinary parameterized query and
update operations that the fluent facade supports. Adapters MUST NOT create duplicate application-wide JdbcClient
configuration when Boot can derive it from the configured JDBC Templates.

#### Scenario: Store executes a common query
- **WHEN** an Inventory JDBC Store executes a positional or named-parameter query with scalar, collection or mapped
  results
- **THEN** it uses the injected managed JdbcClient while preserving the SQL, parameter values and result cardinality

#### Scenario: Store maps a hierarchical projection
- **WHEN** one query returns flattened rows that must be accumulated into a hierarchical immutable view
- **THEN** JdbcClient delegates the rows to the existing accumulator without changing row order or query count

### Requirement: Low-level JDBC workflows retain direct Template access
The Inventory Context and its verification support MUST retain direct `JdbcTemplate` or `NamedParameterJdbcTemplate`
access when an operation requires capabilities outside the simplified JdbcClient facade or an explicit low-level test
seam.

#### Scenario: Fixture controls a specific connection
- **WHEN** a migration or integration fixture uses an explicit Connection, `SingleConnectionDataSource`, callback,
  staged DDL or batch operation
- **THEN** it continues using the appropriate lower-level Template rather than adding a JdbcClient wrapper without
  behavioral benefit

#### Scenario: Adapter test counts delegated statements
- **WHEN** a focused Store test uses a counting Template to assert SQL statement count
- **THEN** the JdbcClient under test delegates to that Template and the statement-count assertion remains effective

### Requirement: JdbcClient migration preserves persistence behavior
The JdbcClient migration SHALL preserve SQL text, query count, parameter values, ordering, row mapping, transaction
participation, exception semantics and externally observable Inventory behavior.

#### Scenario: Existing Inventory workflows execute after migration
- **WHEN** allocation, backlog reconciliation, visibility and diagnostic workflows use migrated JDBC Stores
- **THEN** their returned projections and persisted outcomes are equivalent to the pre-migration behavior
