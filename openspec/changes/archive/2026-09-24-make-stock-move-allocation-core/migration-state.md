# Migration state before replacing V21-V29

Checked on 2026-08-27. No configured or running non-disposable ArchOne database was detected from this workspace, so there is no
database on which the worktree-only V21-V29 drafts can be observed as applied.

## Evidence

- Production configuration obtains its connection exclusively from `ORDER_PROMISING_DB_URL`, `ORDER_PROMISING_DB_USERNAME`, and
  `ORDER_PROMISING_DB_PASSWORD`; all three variables are absent from the implementation shell. Values were not printed.
- Development configuration defaults to `jdbc:postgresql://localhost:28291/order_promising`; no process is listening on TCP port
  28291.
- `docker ps` has no running container whose name matches `archone` or `order-promising`.
- The repository contains no deployment script, SQL fixture, or runbook claiming that V21-V29 was published or applied. Git reports
  all nine files as untracked.

## Decision

Treat V21-V29 as unpublished drafts and replace them in place with the move-centric chain. Do not create forward-repair migrations
for the superseded `allocations`/`allocation_slices` shape because doing so would publish a model that has never left this worktree.

This conclusion is intentionally limited: it does not assert anything about an unconfigured external database. If a database is
later supplied whose `flyway_schema_history` contains any successful V21-V29 row or whose schema contains `allocations` or
`allocation_slices`, stop deployment. Do not run `repair`. Capture its versions/checksums and use an explicit forward migration from
the actually published schema instead of the replacement chain.

## Deployment preflight

Before the first deployment containing the replacement files, run this read-only query against the target database and require an
empty result:

```sql
SELECT installed_rank, version, description, checksum, success
  FROM flyway_schema_history
 WHERE version::integer BETWEEN 21 AND 29
 ORDER BY installed_rank;
```

Also require both probes below to return `NULL`:

```sql
SELECT to_regclass('public.allocations');
SELECT to_regclass('public.allocation_slices');
```

An empty result is the single-writer cutover precondition for replacing these files. A non-empty result changes the migration path
and must be reviewed before application startup.
