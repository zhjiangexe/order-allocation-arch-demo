## Why

Inventory data-access ports must express two independent facts: which use-case side owns the dependency and what each
method does. The first implementation collapsed both facts into the suffix, so command-owned pure reads were renamed
to `*Store` and several former Repositories were moved without actually separating read methods from mutation methods.
That makes `Store` misleading and prevents a reviewer from recognizing persistence effects from the interface alone.

## What Changes

- **BREAKING** Keep every Inventory-owned data-access port in Application while separating pure reads from mutations.
- Reserve `*Finder` for pure read methods, whether the Finder supplies a command or a query use case.
- Reserve `*Store` for state-changing and transaction-locking methods only.
- Let a command use case depend on a command-owned Finder to load state, invoke Domain behavior, and use a Store to
  persist the result.
- Keep query read-model Finders separately owned under query-port packages; query use cases never depend on Stores.
- Split mixed interfaces instead of mechanically renaming Domain Repositories to Stores.
- Restore allocation supply, candidate and backlog ports to command-owned Finders because their methods are pure
  reads.
- Keep Spring Data `Jpa*Repository` interfaces as Infrastructure implementation details.
- Preserve SQL, transaction, lock, algorithm, schema and public contract behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `inventory-data-access-port-conventions`: Makes package ownership and method semantics independent: command/query
  packages identify use-case ownership, while Store/Finder suffixes identify mutation/lock versus pure read methods.

## Impact

- Inventory Application ports, persistence adapter names, constructor dependencies, fixtures, tests, architecture
  rules and documentation.
- Internal Java imports and type names are breaking; REST, event, Temporal, WMS and database contracts are unchanged.
- The earlier command-side-Store interpretation in this change is superseded before archive.
