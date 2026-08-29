## Context

`inventory-context` is one bounded context organized feature-first into `movement`, `allocation`, `balance`, and `warehouse`.
Each capability repeats Clean Architecture layers, but its Domain layer is further split by Java type taxonomy. Application features
also put their own records into `model` buckets, and movement persistence ports currently sit below an ownership-free `shared`
package. With only 3 to 12 Domain types per capability, this hierarchy adds navigation cost without isolating independently evolving
models.

After removing those tactical-role buckets, all four capabilities still mix two primary axes. Application code already exposes
workflows such as receipt, stock view, registration, and operation view, while Domain, entrypoints, and infrastructure remain
layer-first. Assignment exposed this first because it is largest, but retaining a different navigation rule for Balance, Movement,
and Warehouse would leave Inventory with two competing package grammars.

`InventoryBoundaryArchitectureTest` currently mixes stable dependency policies with source-text assertions about exact files, names,
method bodies, modifiers, variable names, deleted legacy types, and living-document wording. Those assertions turn a legal package or
class refactor into an apparent architecture regression.

The worktree already contains the completed Inventory redesign. This change must preserve those changes and remain a package-only
refactor: no database, persisted representation, business algorithm, transaction, API, or event change is authorized.

## Goals / Non-Goals

**Goals:**

- Keep the four existing Inventory capabilities and make their ownership visually obvious.
- Make every Inventory capability business-capability-first so a workflow can be followed from entrypoint through Application and
  Domain to adapters without traversing unrelated code.
- Flatten technical type taxonomy inside each capability Domain.
- Keep business feature folders in Application while removing feature-local `model` buckets.
- Retain explicit `port` packages and remove only the ambiguous movement `shared` package.
- Replace implementation-shape architecture assertions with generic, stable dependency and package-convention fitness functions.
- Preserve all runtime and external behavior.

**Non-Goals:**

- Introducing `stock-core`, `inventory-domain`, or another umbrella package or aggregate.
- Renaming `movement`, `allocation`, `balance`, `warehouse`, or their domain types.
- Reassigning lifecycle or cancellation ownership between top-level capabilities; that requires a separate behavioral ownership
  decision.
- Treating Allocation's internal slices as bounded contexts, deployable modules, or separately persisted subsystems.
- Changing aggregate boundaries, repository methods, SQL, transaction boundaries, allocation rules, or event publication.
- Changing database migrations, REST schemas, integration-event schemas, external identifiers, or persisted enum/string values.

## Decisions

### Keep Inventory capability-first and make every capability slice-first internally

The Inventory top level remains capability-first. Every capability then uses one business-capability axis before its layers:

```text
inventory/allocation/
  assignment/{domain,application,entrypoint,infrastructure}
  cancellation/{domain,application,entrypoint,infrastructure}
  intake/{application,entrypoint,infrastructure}
  lifecycle/{application,entrypoint,infrastructure}
  support/{entrypoint,infrastructure}

inventory/balance/
  onhand/{domain,infrastructure}
  receipt/{application,entrypoint,infrastructure}
  visibility/{application,entrypoint,infrastructure}

inventory/movement/
  operation/{domain,application,infrastructure}
  registration/{application}
  visibility/{application,entrypoint,infrastructure}

inventory/warehouse/
  location/{domain,application,entrypoint,infrastructure}
  operationtype/{domain,infrastructure}
```

These names describe capabilities inside one bounded context, not independent bounded contexts or a parent/child domain hierarchy.
`StockOperation`, `StockMove`, and `StockMoveLine` form the canonical movement model; `StockQuant` remains the balance model; Allocation
connects movement demand to balance supply. No package will claim that all of these concepts are Movement or Allocation.

All listed children are internal business-capability slices, not DDD subdomains. A layer is created inside a slice only when that
slice owns code of that kind. Intake, Lifecycle, On-hand, Registration, and Operation Type therefore omit layers they do not own.
Truly cross-slice Allocation subscription identities and retry observation live in its narrow `support` slice, but business
implementations and adapters must not use it as a miscellaneous bucket.

Balance On-hand owns the canonical `StockQuant` model and persistence. Receipt completes inbound Movement and changes On-hand before
publishing availability; Visibility owns the stock query projection and REST endpoint. Movement Operation owns the canonical
`StockOperation`, `StockMove`, and `StockMoveLine` model, persistence ports, and adapters; Registration creates that model from an
external source, while Visibility owns its query, reconciliation, health, and REST surfaces. Warehouse Location owns stock endpoints
and its listing API; Operation Type owns the facility/direction configuration used to construct Movements.

Alternative considered: a single `inventory/domain/{movement,allocation,balance,warehouse}` layer-first tree. Rejected because it
scatters vertical workflows across the entire module and weakens feature ownership.

Alternative considered: use vertical slices only for Allocation because it is larger. Rejected because source growth would repeatedly
cross a layer-first/slice-first threshold, and developers would need a different navigation rule for each Inventory capability.

### Flatten Domain taxonomy packages

Domain types move directly under the `domain` package of the business slice that owns the rule:

```text
allocation/domain/{aggregate,repository,service,type,valueobject} -> allocation/{assignment,cancellation}/domain
balance/domain/{aggregate,repository,service}                     -> balance/onhand/domain
movement/domain/model/{operation,move}                             -> movement/operation/domain
warehouse/domain/{aggregate,repository,type}                       -> warehouse/{location,operationtype}/domain
```

Package names such as `aggregate`, `valueobject`, and `service` describe Java/DDD tactical roles, not business ownership. The existing
type counts are small enough that a flat package is easier to scan. Class names continue to communicate their roles.

Alternative considered: keep one child package per aggregate. Rejected because no current child package is an independently
encapsulated Java module and their types already collaborate across those package seams.

### Flatten feature-local Application models but retain ports

Records and working objects below `application/<feature>/model` initially move to `application/<feature>`. Allocation then promotes
that feature to the primary internal axis: `allocation/application/<feature>` becomes
`allocation/<feature>/application`. Ports remain under each slice's `application/port`, because they define a dependency direction
that adapters implement. The movement-wide persistence ports move from `movement/application/shared/port` to
`movement/application/port`; within a movement capability, `shared` conveys no owner.

Outside Allocation, existing Application features become the primary internal axis: `balance/application/receipt` becomes
`balance/receipt/application`; stock and operation read models use the common business term `visibility`; and
`movement/application/registration` becomes `movement/registration/application`. Allocation Order intake classes live directly in
`allocation/intake/application`; `order` remains explicit in class names and its source port rather than adding another one-class
package level.

### Keep slice dependencies explicit

Within every Allocation slice, dependencies point inward:

```text
entrypoint -> application -> domain
infrastructure -> application.port and domain
domain -> no Application, infrastructure, or entrypoint dependency
```

Assignment is the core Allocation slice: it owns the proposal-to-reservation working models `StockOperationComposite`,
`MoveQuantAllocationSet`, and `StockOperationLifecycleSnapshot`. Order Intake invokes Assignment after registering the source
movement, while Cancellation and Lifecycle reuse those working models to release or consume an existing assignment. Assignment does
not depend on Intake, Cancellation, or Lifecycle; Intake depends only on Assignment; Lifecycle depends only on Assignment; and
Cancellation may additionally invoke Lifecycle's release/publication API. This forms an explicit acyclic dependency graph rather than
pretending the current workflows are independent. Cancellation and Lifecycle continue to operate on the canonical Movement model
instead of duplicating it in Allocation. Cross-slice event subscription identity and retry observation stay in `allocation.support`
because splitting them would duplicate stable consumer identity and retry policy; architecture tests shall prevent that exception
from becoming a general business-code bucket.

The remaining capability graphs are also explicit:

```text
balance:   onhand <- receipt; onhand <- visibility
movement:  operation <- registration; operation <- visibility
warehouse: location <- operationtype
```

Receipt and Balance Visibility do not depend on each other. Registration and Movement Visibility do not depend on each other.
Location does not depend on Operation Type. These rules protect the core model from edge workflows while permitting an edge slice to
use the canonical model instead of duplicating it.

### Architecture tests protect policies, not implementation morphology

Inventory architecture fitness functions SHALL retain generic rules for:

- bounded-context isolation and foreign table ownership;
- capability dependency direction;
- framework-free Domain code;
- transport/contract-independent Application code;
- explicit Application port ownership; and
- absence of the retired technical taxonomy packages.

They SHALL no longer inspect exact method bodies, local property names, class modifiers, explicit file lists, living-document prose, or
specific removed implementation names. Behavioral invariants remain in unit, integration, contract, and E2E tests. ArchUnit bytecode
rules are preferred for type dependencies; source scanning remains only where bytecode cannot reveal a boundary, such as SQL table
names or package-directory conventions.

### Perform a mechanical migration with no compatibility bridge

All package declarations and direct callers move in one source change. No deprecated forwarding types are introduced because these
are internal Java packages and compatibility wrappers would recreate the hierarchy being removed. Compilation and the full test stack
are the migration safety net.

## Risks / Trade-offs

- **Large import diff can hide an accidental logic edit** → Move declarations mechanically, inspect non-import diffs separately, and
  run formatter plus behavior suites.
- **Flat packages can eventually become crowded** → Add a child package only when a real business concept has independently evolving
  rules, not merely because a Java role exists.
- **Removing morphology assertions can appear to weaken protection** → Retain generic dependency/package rules and rely on focused unit
  and contract tests for behavioral shape.
- **Existing AspectJ expressions or source-path tests may silently target old packages** → Inventory all package-sensitive strings and
  update them as part of the same migration.
- **Dirty worktree changes may be overwritten** → Limit movement and replacement to the explicitly inventoried Inventory files and
  their direct callers; never reset or delete unrelated files.

## Migration Plan

1. Record every source and test type under the packages being flattened, including string-based package references.
2. Refactor architecture tests to express the target generic rules without requiring the old paths.
3. Move Domain types capability by capability and compile after each capability.
4. Move Application models and movement ports feature by feature, updating all production, fixture, SIT, AspectJ, and documentation
   callers.
5. Move Allocation assignment, cancellation, intake, and lifecycle slices across Domain, Application, infrastructure, and entrypoint
   packages; update all compiled and string-based references.
6. Move Balance On-hand, Receipt, and Visibility; Movement Operation, Registration, and Visibility; and Warehouse Location and
   Operation Type into slice-first packages.
7. Add generic rules for every Inventory slice's placement and inward dependencies, then remove only empty retired directories.
8. Run Palantir formatting, Inventory tests, targeted SIT, full backend tests, E2E, and strict OpenSpec validation.

Rollback is a source-level reversal of this change; no data rollback is needed because no persisted representation changes.

## Open Questions

None. Lifecycle/cancellation remain owned by Allocation in this change; the new packages make that current ownership explicit without
claiming they are permanent DDD subdomains.
