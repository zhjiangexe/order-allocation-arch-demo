## 1. Freeze the allocation contract

- [ ] 1.1 Confirm the `AllocationDemand` status transitions are limited to `PENDING`, `ALLOCATED`, and `CANCELLED`.
- [ ] 1.2 Confirm the source identity contract, including whether `sourceSystem` is required for uniqueness.
- [ ] 1.3 Confirm that one allocation demand contains only one owner, facility, and source location.
- [ ] 1.4 Add architecture tests preventing allocation code from using source-specific aggregates as its decision input.

## 2. Add allocation-demand persistence and domain model

- [ ] 2.1 Add the migration for allocation demand headers, including source identity, scope, allocation status, version, and timestamps.
- [ ] 2.2 Add the migration for allocation demand lines, including source-line reference, SKU, quantity, and demand ordering.
- [ ] 2.3 Add the unique constraint for allocation source identity and indexes for pending scope/FIFO candidate queries.
- [ ] 2.4 Implement `AllocationDemand` and `AllocationDemandLine` domain models with the allowed state transitions.
- [ ] 2.5 Implement source type and source reference value objects with validation.
- [ ] 2.6 Implement persistence entities, mappers, repositories, and optimistic-locking support.
- [ ] 2.7 Add unit and persistence tests for creation, idempotent retry, invalid quantities, invalid transitions, and cancellation.
- [ ] 2.8 Add model/architecture tests proving `AllocationDemand` contains only common allocation data and does not depend on source-specific aggregate status or fulfillment transitions.

## 3. Create source adapters and execution links

- [ ] 3.1 Add an application command/use case for creating an allocation demand idempotently from a source reference.
- [ ] 3.2 Link each stock-consuming outbound movement to its allocation demand and demand line.
- [ ] 3.3 Keep inbound movement creation supply-only and verify that it never creates an allocation demand reference.
- [ ] 3.4 Add the order source adapter that creates an `ORDER` allocation demand and its outbound movements.
- [ ] 3.5 Add migration/backfill handling for existing pending order movements without creating duplicate demands.
- [ ] 3.6 Add tests proving transfer/replenishment/manual source types can create demands without an order id.

## 4. Replace candidate discovery with demand-first queries

- [ ] 4.1 Add a repository query that returns pending allocation-demand candidates with all demand lines and execution references.
- [ ] 4.2 Filter candidate queries by owner, facility, source location, and triggering SKU scope.
- [ ] 4.3 Exclude cancelled/completed execution records and inbound movements without using `orderId` as the generic demand predicate.
- [ ] 4.4 Ensure candidate limits count allocation demands and preserve FIFO ordering across all demand lines.
- [ ] 4.5 Load all candidate SKUs' allocatable stock in a bounded number of queries before planning.
- [ ] 4.6 Add repository tests for order and non-order candidates, inbound exclusion, multi-SKU candidates, same-demand multi-SKU scopes, and scope limits.

## 5. Separate planning from execution commit

- [ ] 5.1 Refactor the allocation decision service to accept generic demand inputs and return an immutable allocation plan without repository access.
- [ ] 5.2 Implement `AllocationCommitter` to reserve stock batches in global order and apply the plan to demand lines.
- [ ] 5.3 Update outbound `StockMove` state and create `StockMoveLine` records from committed batch picks.
- [ ] 5.4 Update optional `StockPicking` summaries in the same transaction as move and demand state changes.
- [ ] 5.5 Transition `AllocationDemand` to `ALLOCATED` only after every demand line is committed successfully.
- [ ] 5.6 Preserve rollback behavior for optimistic-locking failures and ensure no completion fact is published after rollback.
- [ ] 5.7 Add unit tests for complete allocation, insufficient multi-SKU stock, FEFO picks, FIFO candidate selection, and empty/no-op batches.

## 6. Rewire initial allocation and waiting reconciliation

- [ ] 6.1 Refactor initial allocation to use the generic demand creator, planner, and committer without changing its external trigger contract.
- [ ] 6.2 Refactor `AllocateWaitingDemandUsecase` to consume demand-first candidates instead of reconstructing demand from `StockMove`.
- [ ] 6.3 Replace `MovementAssigner.toDemands` with the new candidate/commit boundary and remove redundant movement-to-demand conversion.
- [ ] 6.4 Keep availability events and reconciliation scheduler as separate triggers of the same bounded allocation transaction.
- [ ] 6.5 Ensure duplicate wake-ups for the same demand are safe under optimistic locking and idempotent commit.
- [ ] 6.6 Update unit tests for initial allocation, waiting allocation, scheduler isolation, duplicate events, and concurrent allocation.

## 7. Generalize completion and cancellation facts

- [ ] 7.1 Define the generic allocation completion fact with allocation-demand and source references.
- [ ] 7.2 Update outbox translation and integration contracts for generic completion while retaining source-specific handling at consumers.
- [ ] 7.3 Add the order completion adapter and verify that order status changes remain outside the allocation transaction.
- [ ] 7.4 Implement cancellation of pending demands and unassigned movements.
- [ ] 7.5 Implement cancellation of allocated demands with reservation release and idempotent retry behavior.
- [ ] 7.6 Add tests for cancellation/allocation races and source-specific completion routing.

## 8. Add non-order source support incrementally

- [ ] 8.1 Add the internal-transfer adapter and its completion/cancellation integration tests.
- [ ] 8.2 Add the replenishment adapter only for replenishment operations that compete for source stock.
- [ ] 8.3 Add the production-issue adapter if the source process requires allocation before execution.
- [ ] 8.4 Add the manual-outbound adapter with source idempotency and cancellation behavior.
- [ ] 8.5 Verify that supply-only inbound, inventory adjustment, and already-reserved operations do not create allocation demands.

## 9. Remove legacy order-specific queue paths

- [ ] 9.1 Run dual-read comparison between the legacy order/movement candidate query and the new allocation-demand candidate query.
- [ ] 9.2 Verify that FIFO, FEFO, ship-complete, availability wake-up, cancellation, and concurrency integration tests produce equivalent order behavior.
- [ ] 9.3 Remove the `picking.orderId`-based generic waiting predicate after all source adapters are enabled.
- [ ] 9.4 Remove obsolete order-specific demand reconstruction and `MovementAssigner` waiting-query dependencies.
- [ ] 9.5 Remove obsolete event names and contracts only after all consumers use the generic allocation fact.
- [ ] 9.6 Update living specs, architecture documentation, and migration notes with the final allocation-demand boundary.

## 10. Verification and rollout

- [ ] 10.1 Run module unit tests and persistence tests for allocation demand, stock allocation, and stock movement.
- [ ] 10.2 Run end-to-end tests for order, inbound receipt, availability wake-up, scheduler reconciliation, cancellation, and non-order outbound sources.
- [ ] 10.3 Verify database indexes and query plans for pending demand scope queries before removing legacy indexes.
- [ ] 10.4 Exercise migration/backfill against representative existing data and verify no duplicate source identities.
- [ ] 10.5 Document the feature switch/rollback procedure and confirm legacy order allocation remains recoverable until cutover.
