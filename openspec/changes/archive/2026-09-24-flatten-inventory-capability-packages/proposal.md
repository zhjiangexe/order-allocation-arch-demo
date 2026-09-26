## Why

Inventory is already one bounded context, but its four internal capabilities are nested under technical taxonomy packages such as
`aggregate`, `valueobject`, `service`, `type`, `model`, and `shared`. The resulting depth obscures ownership, while source-text
architecture tests pin exact class names, paths, and method bodies so tightly that behavior-preserving refactors look like architecture
violations.

The first flattening pass also exposed an inconsistent organizing axis inside every Inventory capability: layers remain the primary
axis even where Application already names cohesive workflows. One receipt, registration, visibility, location, or assignment flow is
therefore scattered across distant trees even though its use cases, models, adapters, and triggers evolve together.

## What Changes

- Keep `movement`, `allocation`, `balance`, and `warehouse` as Inventory capabilities rather than introducing another umbrella domain.
- Organize every Inventory capability by an internal business capability before applying Clean Architecture layers inside that
  slice: Allocation uses `assignment`, `cancellation`, `intake`, `lifecycle`, and narrow `support`; Balance uses `onhand`, `receipt`,
  and `visibility`; Movement uses `operation`, `registration`, and `visibility`; Warehouse uses `location` and `operationtype`.
- Flatten capability Domain packages so domain types live directly under each capability's `domain` package.
- Flatten feature-local Application `model` buckets while retaining explicit `port` packages as dependency boundaries.
- Remove the ambiguous `movement/application/shared` package and place shared movement persistence ports directly under
  `movement/application/port`.
- Reclassify stable dependency fitness functions separately from implementation-shape assertions, retaining only rules that protect
  bounded-context, layer, ownership, and framework boundaries.
- Preserve all persistence mappings, transactions, REST schemas, integration-event schemas, external identifiers, and runtime behavior.

## Capabilities

### New Capabilities

- `inventory-module-architecture`: Defines the stable Inventory capability/package organization and the architectural boundaries that
  build-time fitness functions must enforce without pinning implementation shape.

### Modified Capabilities

None. This is an internal code-organization refactor and does not change existing business requirements.

## Impact

- Affects Inventory production and test package declarations plus direct callers in monolith composition, WMS, Temporal, SIT fixtures,
  and architecture tests.
- Co-locates each Inventory trigger-to-domain-to-adapter flow without treating the internal slices as independent bounded contexts.
- Removes obsolete technical package directories only after all callers compile.
- Introduces no database migration, dependency, API, event, persisted-value, or business-algorithm change.
