package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.command.CancelMovementsCommand;
import com.flowzati.archone.inventory.allocation.application.service.cancellation.AllocationReservationCanceller;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationCancellationOperation;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationCancellationOperationRepository;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelMovementsUsecaseTest {

    private static final Instant NOW = Instant.parse("2026-08-18T05:00:00Z");
    private AllocationDemandRepository demandRepository;
    private AllocationCancellationOperationRepository operationRepository;
    private AllocationReservationCanceller allocationReservationCanceller;
    private CancelMovementsUsecase usecase;

    @BeforeEach
    void setUp() {
        demandRepository = mock(AllocationDemandRepository.class);
        operationRepository = mock(AllocationCancellationOperationRepository.class);
        allocationReservationCanceller = mock(AllocationReservationCanceller.class);
        usecase = new CancelMovementsUsecase(
                demandRepository,
                operationRepository,
                allocationReservationCanceller,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(operationRepository.save(any())).thenAnswer(invocation -> {
            AllocationCancellationOperation operation = invocation.getArgument(0);
            return AllocationCancellationOperation.rehydrate(
                    operation.allocationDemandId(),
                    operation.operationId(),
                    operation.startedAt(),
                    operation.updatedAt(),
                    operation.state(),
                    operation.version());
        });
        when(demandRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("order cancellation event 確認後取消 demand execution 並完成 operation checkpoint")
    void shouldCancelDemandUnderStableOperationId() {
        UUID orderId = uuid(1);
        UUID operationId = uuid(2);
        AllocationDemand demand = demand(orderId);
        when(demandRepository.findBySource(SourceAllocationUnit.primaryOrder(orderId.toString())))
                .thenReturn(Optional.of(demand));
        when(operationRepository.find(demand.id(), operationId)).thenReturn(Optional.empty());

        usecase.execute(new CancelMovementsCommand(orderId, operationId));

        verify(allocationReservationCanceller).cancelForDemand(demand.id());
        assertThat(demand.status()).isEqualTo(AllocationDemandStatus.CANCELLED);
        verify(operationRepository)
                .save(org.mockito.ArgumentMatchers.argThat(
                        operation -> operation.state() == AllocationCancellationState.COMPLETED));
    }

    @Test
    @DisplayName("rolling-version row 尚無 demand 時退回 legacy order cancellation")
    void shouldFallbackForPreBackfillExecution() {
        UUID orderId = uuid(3);
        when(demandRepository.findBySource(SourceAllocationUnit.primaryOrder(orderId.toString())))
                .thenReturn(Optional.empty());

        usecase.execute(new CancelMovementsCommand(orderId, uuid(4)));

        verify(allocationReservationCanceller).cancelForOrder(orderId);
    }

    private static AllocationDemand demand(UUID orderId) {
        return AllocationDemand.accept(
                uuid(10),
                SourceAllocationUnit.primaryOrder(orderId.toString()),
                uuid(11),
                uuid(12),
                uuid(13),
                NOW.plusSeconds(3600),
                50,
                NOW.minusSeconds(60),
                List.of(new AllocationDemandLineRequest(uuid(14).toString(), "SKU-A", 1)),
                () -> uuid(15));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
