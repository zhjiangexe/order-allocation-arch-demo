package com.flowzati.archone.stock.allocation.application.usecase;

import com.flowzati.archone.stock.allocation.application.command.CancelMovementsCommand;
import com.flowzati.archone.stock.allocation.application.MovementCanceller;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.stock.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.stock.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.stock.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.stock.allocation.domain.repository.AllocationDemandRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Order cancellation adapter over source-agnostic demand cancellation progress. */
@Service
public class CancelMovementsUsecase {

  private final AllocationDemandRepository demandRepository;
  private final AllocationCancellationOperationRepository operationRepository;
  private final MovementCanceller movementCanceller;
  private final Clock clock;

  public CancelMovementsUsecase(
      AllocationDemandRepository demandRepository,
      AllocationCancellationOperationRepository operationRepository,
      MovementCanceller movementCanceller,
      Clock clock) {
    this.demandRepository = demandRepository;
    this.operationRepository = operationRepository;
    this.movementCanceller = movementCanceller;
    this.clock = clock;
  }

  /** OrderCancelled v1 is itself the required external execution-cancellation confirmation. */
  @Transactional
  public void execute(CancelMovementsCommand command) {
    Optional<AllocationDemand> found = demandRepository.findBySource(
        SourceAllocationUnit.primaryOrder(command.orderId().toString()));
    if (found.isEmpty()) {
      // Rolling-version fallback for a row created by an old binary before demand backfill.
      movementCanceller.cancelForOrder(command.orderId());
      return;
    }

    AllocationDemand demand = found.get();
    Instant now = clock.instant();
    AllocationCancellationOperation operation = operationRepository.find(
            demand.id(), command.cancellationOperationId())
        .orElseGet(() -> operationRepository.save(AllocationCancellationOperation.start(
            demand.id(), command.cancellationOperationId(), now)));
    if (operation.state() == AllocationCancellationState.COMPLETED
        || operation.state() == AllocationCancellationState.EXTERNAL_REJECTED) {
      return;
    }
    if (operation.state() == AllocationCancellationState.STARTED) {
      operation.confirmExternally(now);
      operation = operationRepository.save(operation);
    }

    movementCanceller.cancelForDemand(demand.id());
    if (demand.status() == AllocationDemandStatus.PENDING) {
      demand.cancelPending();
      demandRepository.save(demand);
    } else if (demand.status() == AllocationDemandStatus.ALLOCATED) {
      demand.cancelAllocatedAfterExecutionStopped();
      demandRepository.save(demand);
    }
    operation.completeLocally(now);
    operationRepository.save(operation);
  }
}
