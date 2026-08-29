## REMOVED Requirements

### Requirement: A stock-consuming source creates an allocation demand

**Reason**: `AllocationDemand` duplicates the requirement already represented by a confirmed `StockMove` and delays creation of the
movement until after reservation.

**Migration**: Register one confirmed `StockPicking` per source allocation unit and one confirmed `StockMove` per canonical source line.

### Requirement: Source-specific behavior remains outside allocation demand

**Reason**: The source boundary remains necessary, but it no longer requires an allocation-demand aggregate.

**Migration**: Source adapters normalize source identity and line identity into picking and move registration commands without loading
the source aggregate inside allocation.

### Requirement: Allocation demand creation is idempotent per source allocation unit

**Reason**: Idempotency belongs to the accepted inventory operation group rather than a duplicate demand header.

**Migration**: Enforce uniqueness for `(sourceType, sourceId, allocationUnitKey)` on `StockPicking` and compare immutable picking/move
content on replay.

### Requirement: Allocation demand owns only allocation state

**Reason**: Pending, assigned, done and cancelled are stages of the movement lifecycle; a parallel demand status can drift from moves.

**Migration**: Use `StockMove` as the canonical line lifecycle and `StockPicking.state` only as its transactionally maintained summary.

### Requirement: An allocation demand has one inventory scope

**Reason**: Scope and route are already required to construct a coherent inventory operation and its movements.

**Migration**: Store operation scope on `StockPicking` and explicit source/destination endpoints on each `StockMove`; split multi-source
requests before registration.

### Requirement: Allocation candidates are demand-first and execution-aware

**Reason**: A confirmed movement is the durable pending requirement, so candidate selection no longer needs a separate demand record or
target-existence predicate.

**Migration**: Select confirmed stock-consuming pickings and their complete confirmed move sets.

### Requirement: Allocation cancellation stops only reversible execution

**Reason**: Cancellation must operate on the canonical movement group and reservation lines instead of coordinating a duplicate demand
lifecycle with materialized targets.

**Migration**: Key cancellation operations by `pickingId`; release assigned move lines and quant counters before cancelling reversible
moves and their picking.
