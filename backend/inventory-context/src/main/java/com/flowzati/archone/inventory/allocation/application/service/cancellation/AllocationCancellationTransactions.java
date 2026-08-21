package com.flowzati.archone.inventory.allocation.application.service.cancellation;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Short local transactions around an external cancellation call. */
@Component
public class AllocationCancellationTransactions {

    private final AllocationDemandRepository demandRepository;
    private final AllocationCancellationOperationRepository operationRepository;
    private final AllocationReservationCanceller allocationReservationCanceller;

    public AllocationCancellationTransactions(
            AllocationDemandRepository demandRepository,
            AllocationCancellationOperationRepository operationRepository,
            AllocationReservationCanceller allocationReservationCanceller) {
        this.demandRepository = demandRepository;
        this.operationRepository = operationRepository;
        this.allocationReservationCanceller = allocationReservationCanceller;
    }

    @Transactional
    public AllocationCancellationStepResult begin(UUID demandId, UUID operationId, Instant now) {
        AllocationDemand demand = demandRepository
                .findById(demandId)
                .orElseThrow(() -> new IllegalStateException("Allocation demand no longer exists: " + demandId));
        AllocationCancellationOperation operation = operationRepository
                .find(demandId, operationId)
                .orElseGet(() ->
                        operationRepository.save(AllocationCancellationOperation.start(demandId, operationId, now)));
        return new AllocationCancellationStepResult(demand, operation.state());
    }

    @Transactional
    public AllocationCancellationStepResult recordExternalDecision(
            UUID demandId, UUID operationId, ExternalCancellationDecision decision, Instant now) {
        AllocationDemand demand = demandRepository
                .findById(demandId)
                .orElseThrow(() -> new IllegalStateException("Allocation demand no longer exists: " + demandId));
        AllocationCancellationOperation operation = operationRepository
                .find(demandId, operationId)
                .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
        if (operation.state() == AllocationCancellationState.STARTED) {
            if (decision == ExternalCancellationDecision.CONFIRMED) {
                operation.confirmExternally(now);
            } else {
                operation.rejectExternally(now);
            }
            operation = operationRepository.save(operation);
        }
        return new AllocationCancellationStepResult(demand, operation.state());
    }

    /**
     * Atomically cancels a still-pending demand, or returns a fresh allocated snapshot when
     * allocation won the race and external execution coordination has become mandatory.
     */
    @Transactional
    public AllocationCancellationStepResult completePendingOrRefresh(UUID demandId, UUID operationId, Instant now) {
        AllocationCancellationOperation operation = operationRepository
                .find(demandId, operationId)
                .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
        AllocationDemand demand = demandRepository
                .findById(demandId)
                .orElseThrow(() -> new IllegalStateException("Allocation demand no longer exists: " + demandId));
        if (operation.state() != AllocationCancellationState.STARTED) {
            return new AllocationCancellationStepResult(demand, operation.state());
        }
        if (demand.status() == AllocationDemandStatus.ALLOCATED) {
            return new AllocationCancellationStepResult(demand, operation.state());
        }

        if (demand.status() == AllocationDemandStatus.PENDING) {
            allocationReservationCanceller.cancelForDemand(demand.id());
            demand.cancelPending();
            demandRepository.save(demand);
        }
        // PENDING has no external execution; CANCELLED is already locally final. Both can complete
        // the operation without making an external call.
        operation.confirmExternally(now);
        operation.completeLocally(now);
        operationRepository.save(operation);
        return new AllocationCancellationStepResult(demand, operation.state());
    }

    @Transactional
    public AllocationCancellationStatus complete(UUID demandId, UUID operationId, Instant now) {
        AllocationCancellationOperation operation = operationRepository
                .find(demandId, operationId)
                .orElseThrow(() -> new IllegalStateException("Cancellation operation was not started"));
        if (operation.state() == AllocationCancellationState.COMPLETED) {
            return AllocationCancellationStatus.COMPLETED;
        }
        if (operation.state() == AllocationCancellationState.EXTERNAL_REJECTED) {
            return AllocationCancellationStatus.NOT_CANCELLABLE;
        }
        if (operation.state() != AllocationCancellationState.EXTERNAL_CONFIRMED) {
            throw new IllegalStateException("Local cancellation requires durable external confirmation");
        }

        AllocationDemand demand = demandRepository
                .findById(demandId)
                .orElseThrow(() -> new IllegalStateException("Allocation demand no longer exists: " + demandId));
        if (demand.status() != AllocationDemandStatus.CANCELLED) {
            allocationReservationCanceller.cancelForDemand(demand.id());
            if (demand.status() == AllocationDemandStatus.PENDING) {
                demand.cancelPending();
            } else if (demand.status() == AllocationDemandStatus.ALLOCATED) {
                demand.cancelAllocatedAfterExecutionStopped();
            }
            demandRepository.save(demand);
        }
        operation.completeLocally(now);
        operationRepository.save(operation);
        return AllocationCancellationStatus.COMPLETED;
    }
}
