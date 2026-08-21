package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.command.CancelAllocationDemandCommand;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationCancellationStatus;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationCancellationStepResult;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationCancellationTransactions;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationExecutionCancellationCoordinator;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.ExternalCancellationDecision;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Source-agnostic idempotent cancellation saga.
 * Deliberately has no transaction: external coordination occurs between short local checkpoints.
 */
@Service
public class CancelAllocationDemandUsecase {

    private final AllocationCancellationTransactions transactions;
    private final AllocationExecutionCancellationCoordinator externalCoordinator;
    private final Clock clock;

    public CancelAllocationDemandUsecase(
            AllocationCancellationTransactions transactions,
            AllocationExecutionCancellationCoordinator externalCoordinator,
            Clock clock) {
        this.transactions = transactions;
        this.externalCoordinator = externalCoordinator;
        this.clock = clock;
    }

    public AllocationCancellationStatus execute(CancelAllocationDemandCommand command) {
        AllocationCancellationStepResult checkpoint =
                transactions.begin(command.allocationDemandId(), command.cancellationOperationId(), clock.instant());
        if (checkpoint.state() == AllocationCancellationState.COMPLETED) {
            return AllocationCancellationStatus.COMPLETED;
        }
        if (checkpoint.state() == AllocationCancellationState.EXTERNAL_REJECTED) {
            return AllocationCancellationStatus.NOT_CANCELLABLE;
        }

        if (checkpoint.state() == AllocationCancellationState.STARTED) {
            if (checkpoint.demand().status() != AllocationDemandStatus.ALLOCATED) {
                checkpoint = transactions.completePendingOrRefresh(
                        command.allocationDemandId(), command.cancellationOperationId(), clock.instant());
                if (checkpoint.state() == AllocationCancellationState.COMPLETED) {
                    return AllocationCancellationStatus.COMPLETED;
                }
            }
            if (checkpoint.state() == AllocationCancellationState.STARTED) {
                ExternalCancellationDecision decision =
                        externalCoordinator.cancelExecution(checkpoint.demand(), command.cancellationOperationId());
                checkpoint = transactions.recordExternalDecision(
                        command.allocationDemandId(), command.cancellationOperationId(), decision, clock.instant());
            }
        }
        if (checkpoint.state() == AllocationCancellationState.EXTERNAL_REJECTED) {
            return AllocationCancellationStatus.NOT_CANCELLABLE;
        }
        return transactions.complete(command.allocationDemandId(), command.cancellationOperationId(), clock.instant());
    }
}
