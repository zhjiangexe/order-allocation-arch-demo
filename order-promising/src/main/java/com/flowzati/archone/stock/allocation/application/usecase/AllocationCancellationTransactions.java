package com.flowzati.archone.stock.allocation.application.usecase;

import com.flowzati.archone.stock.allocation.application.ExternalCancellationDecision;
import com.flowzati.archone.stock.allocation.application.MovementCanceller;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.stock.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.stock.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.stock.allocation.domain.repository.AllocationDemandRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Short local transactions around an external cancellation call. */
@Component
public class AllocationCancellationTransactions {

  private final AllocationDemandRepository demandRepository;
  private final AllocationCancellationOperationRepository operationRepository;
  private final MovementCanceller movementCanceller;

  public AllocationCancellationTransactions(
      AllocationDemandRepository demandRepository,
      AllocationCancellationOperationRepository operationRepository,
      MovementCanceller movementCanceller) {
    this.demandRepository = demandRepository;
    this.operationRepository = operationRepository;
    this.movementCanceller = movementCanceller;
  }

  @Transactional
  public AllocationCancellationCheckpoint begin(
      UUID demandId, UUID operationId, Instant now) {
    AllocationDemand demand = demandRepository.findById(demandId)
        .orElseThrow(() -> new IllegalStateException(
            "Allocation demand no longer exists: " + demandId));
    AllocationCancellationOperation operation = operationRepository.find(demandId, operationId)
        .orElseGet(() -> operationRepository.save(
            AllocationCancellationOperation.start(demandId, operationId, now)));
    return new AllocationCancellationCheckpoint(demand, operation.state());
  }

  @Transactional
  public AllocationCancellationCheckpoint recordExternalDecision(
      UUID demandId,
      UUID operationId,
      ExternalCancellationDecision decision,
      Instant now) {
    AllocationDemand demand = demandRepository.findById(demandId)
        .orElseThrow(() -> new IllegalStateException(
            "Allocation demand no longer exists: " + demandId));
    AllocationCancellationOperation operation = operationRepository.find(demandId, operationId)
        .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
    if (operation.state() == AllocationCancellationState.STARTED) {
      if (decision == ExternalCancellationDecision.CONFIRMED) {
        operation.confirmExternally(now);
      } else {
        operation.rejectExternally(now);
      }
      operation = operationRepository.save(operation);
    }
    return new AllocationCancellationCheckpoint(demand, operation.state());
  }

  /**
   * Atomically cancels a still-pending demand, or returns a fresh allocated snapshot when
   * allocation won the race and external execution coordination has become mandatory.
   */
  @Transactional
  public AllocationCancellationCheckpoint completePendingOrRefresh(
      UUID demandId, UUID operationId, Instant now) {
    AllocationCancellationOperation operation = operationRepository.find(demandId, operationId)
        .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
    AllocationDemand demand = demandRepository.findById(demandId)
        .orElseThrow(() -> new IllegalStateException(
            "Allocation demand no longer exists: " + demandId));
    if (operation.state() != AllocationCancellationState.STARTED) {
      return new AllocationCancellationCheckpoint(demand, operation.state());
    }
    if (demand.status() == AllocationDemandStatus.ALLOCATED) {
      return new AllocationCancellationCheckpoint(demand, operation.state());
    }

    if (demand.status() == AllocationDemandStatus.PENDING) {
      movementCanceller.cancelForDemand(demand.id());
      demand.cancelPending();
      demandRepository.save(demand);
    }
    // PENDING has no external execution; CANCELLED is already locally final. Both can complete
    // the operation without making an external call.
    operation.confirmExternally(now);
    operation.completeLocally(now);
    operationRepository.save(operation);
    return new AllocationCancellationCheckpoint(demand, operation.state());
  }

  @Transactional
  public AllocationCancellationResult complete(
      UUID demandId, UUID operationId, Instant now) {
    AllocationCancellationOperation operation = operationRepository.find(demandId, operationId)
        .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
    if (operation.state() == AllocationCancellationState.COMPLETED) {
      return AllocationCancellationResult.COMPLETED;
    }
    if (operation.state() == AllocationCancellationState.EXTERNAL_REJECTED) {
      return AllocationCancellationResult.NOT_CANCELLABLE;
    }
    if (operation.state() != AllocationCancellationState.EXTERNAL_CONFIRMED) {
      throw new IllegalStateException("Local cancellation requires durable external confirmation");
    }

    AllocationDemand demand = demandRepository.findById(demandId)
        .orElseThrow(() -> new IllegalStateException(
            "Allocation demand no longer exists: " + demandId));
    if (demand.status() != AllocationDemandStatus.CANCELLED) {
      movementCanceller.cancelForDemand(demand.id());
      if (demand.status() == AllocationDemandStatus.PENDING) {
        demand.cancelPending();
      } else if (demand.status() == AllocationDemandStatus.ALLOCATED) {
        demand.cancelAllocatedAfterExecutionStopped();
      }
      demandRepository.save(demand);
    }
    operation.completeLocally(now);
    operationRepository.save(operation);
    return AllocationCancellationResult.COMPLETED;
  }
}
