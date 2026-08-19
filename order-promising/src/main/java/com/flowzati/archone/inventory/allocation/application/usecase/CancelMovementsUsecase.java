package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.command.CancelMovementsCommand;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationReservationCanceller;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
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
  private final AllocationReservationCanceller allocationReservationCanceller;
  private final Clock clock;

  public CancelMovementsUsecase(
      AllocationDemandRepository demandRepository,
      AllocationCancellationOperationRepository operationRepository,
      AllocationReservationCanceller allocationReservationCanceller,
      Clock clock) {
    this.demandRepository = demandRepository;
    this.operationRepository = operationRepository;
    this.allocationReservationCanceller = allocationReservationCanceller;
    this.clock = clock;
  }

  /** OrderCancelled v1 is itself the required external execution-cancellation confirmation. */
  @Transactional
  public void execute(CancelMovementsCommand command) {
    Optional<AllocationDemand> found = demandRepository.findBySource(
        SourceAllocationUnit.primaryOrder(command.orderId().toString()));
    if (found.isEmpty()) {
      // Rolling-version fallback for a row created by an old binary before demand backfill.
      allocationReservationCanceller.cancelForOrder(command.orderId());
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

    allocationReservationCanceller.cancelForDemand(demand.id());
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
