# Inventory ownership implementation baseline

Captured before applying `redefine-inventory-domain-ownership` on 2026-08-29.

## Scope

- Inventory Java type inventory: 198 files. Each row is mapped to its intended owner and includes its pre-change SHA-256.
- Direct caller inventory: 242 files importing or naming an Inventory package.
- Package-sensitive reference inventory: 888 source-path, package-string or pointcut references.
- Dirty-worktree status was captured in the apply session before the first implementation edit. Pre-existing unrelated edits remain user-owned and must not be reverted.

## Green baseline

The following command passed before ownership changes:

```text
cd backend
./gradlew :inventory-context:test :deployments:monolith:test :deployments:monolith:sit \
  :wms-context:compileTestJava :fulfillment-temporal-runtime:compileTestJava
BUILD SUCCESSFUL in 3m 36s
```

## Non-negotiable preservation boundary

This change may alter Java package declarations, imports, source paths and the explicitly approved Java type rename only. It must not alter:

- Flyway migrations, table or column names, entity mappings, persisted enum strings or SQL predicates/order;
- REST paths/JSON, integration-event schemas/subscriber IDs, Temporal or WMS contracts;
- allocation algorithms, proposal revalidation, exact-coverage invariants or lock order;
- transaction annotations, flush timing, publication timing or observable workflow behavior.

## Manifests

- `inventory-types.tsv`: every Inventory production/test-fixture/test Java file and target owner.
- `direct-callers.tsv`: every backend file that directly names an Inventory package and target owner.
- `package-sensitive-references.txt`: exact package/source-path/pointcut references requiring mechanical updates.
