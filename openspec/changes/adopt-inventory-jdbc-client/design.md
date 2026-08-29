## Context

The monolith runs Spring Boot 4.0.7 with Spring JDBC 7.0.8. Boot already auto-configures one `JdbcClient` from the
single `NamedParameterJdbcTemplate`, but no Inventory production adapter currently injects it. Six focused read and
planning Stores use common query operations through either `JdbcTemplate` or `NamedParameterJdbcTemplate`.

`JdbcClient` is a fluent facade over those Templates, not a new driver, connection pool or transaction mechanism.
Migration must therefore reduce adapter ceremony without changing SQL, query count, transaction participation or
projection behavior.

## Goals / Non-Goals

**Goals:**

- Use one Boot-managed `JdbcClient` API for ordinary Inventory JDBC Store queries.
- Remove `MapSqlParameterSource`, empty-result exception control flow and positional overload ceremony where the
  fluent API expresses the same semantics.
- Preserve custom row accumulation for hierarchical projections.
- Verify each migration batch before continuing.

**Non-Goals:**

- Rewriting SQL, consolidating queries or changing indexes and query plans.
- Replacing Template usage in migration/SIT fixtures or explicit single-connection helpers.
- Introducing Spring Data JDBC, jOOQ, an ORM or a custom `JdbcClient` bean.
- Removing `JdbcTemplate` dependencies from the project.

## Decisions

### 1. Inject Boot's auto-configured JdbcClient

Each migrated Store receives `JdbcClient` through its constructor. No project-owned `@Bean JdbcClient` is added,
because Boot creates it from the configured `NamedParameterJdbcTemplate` and retains existing DataSource, transaction
and Template customization.

Alternative considered: call `JdbcClient.create(...)` inside every adapter. Rejected because it duplicates framework
wiring and obscures the single application-level configuration source.

### 2. Migrate in two tested batches

The first batch contains allocation supply, assignment backlog, operation reconciliation and quant view Stores. Their
queries map directly to named or positional `JdbcClient` operations. The second batch contains assignment candidate
and operation view Stores, whose mutable row accumulators must remain intact.

Alternative considered: one mechanical repository-wide replacement. Rejected because low-level fixtures and complex
projections need different migration treatment.

### 3. Preserve SQL and mapping explicitly

SQL text blocks, parameter values, collection expansion, row mappers, accumulator ordering and result cardinality
remain unchanged. `optional()` may replace catching `EmptyResultDataAccessException` only where both forms mean zero
or one result and still reject multiple rows.

### 4. Keep direct Template access for lower-level workflows

Migration and SIT fixtures that construct `SingleConnectionDataSource`, perform connection callbacks, stage DDL or
need Template subclass test seams keep `JdbcTemplate`. Focused Store unit tests may wrap their existing counting
Template with `JdbcClient.create(countingTemplate)` so query-count assertions continue to observe the real delegate.

## Risks / Trade-offs

- [Risk] Named collection binding changes generated SQL. → Preserve the non-empty guard and verify the allocation
  supply integration scenarios.
- [Risk] Fluent result terminals change zero/multiple-row semantics. → Use `optional()`, `single()` and `list()` only
  where their cardinality matches the existing Template call, then run focused tests.
- [Risk] Hierarchical projections lose rows or ordering. → Retain the existing RowCallbackHandler accumulators and
  query-count tests.
- [Trade-off] Both JdbcClient and JdbcTemplate remain in the codebase. → Treat this as an intentional abstraction
  boundary: Client for common Store operations, Template for low-level JDBC control.

## Migration Plan

1. Establish the existing Inventory test suite as the baseline.
2. Migrate the four direct-query Stores and their focused tests; format and run Inventory tests.
3. Migrate the candidate and hierarchical view Stores; format and rerun Inventory tests.
4. Run the complete backend test suite, monolith SIT and project E2E.

Rollback is source-only: restore the six constructor types and Template query calls. No schema or stored data changes
are involved.

## Open Questions

None. Advanced Template usage remains intentionally out of scope.
