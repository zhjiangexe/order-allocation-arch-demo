package com.flowzati.archone.inventory.balance.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.event.OutboundMovementEventPublisher;
import com.flowzati.archone.inventory.balance.application.result.CompleteOutboundMovementsResult.Status;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.event.OutboundMovementsCompleted;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.inventory.movement.domain.type.MoveState;
import com.flowzati.archone.inventory.movement.domain.type.PickingState;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompleteOutboundMovementsUsecaseTest {

    private static final UUID ALLOCATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID SHIPMENT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID PICKING_TYPE_ID = UUID.randomUUID();
    private static final UUID MOVE_ID = UUID.randomUUID();
    private static final UUID DEMAND_LINE_ID = UUID.randomUUID();
    private static final UUID ORDER_LINE_ID = UUID.randomUUID();
    private static final UUID QUANT_ID = UUID.randomUUID();
    private static final UUID FROM_LOCATION_ID = UUID.randomUUID();
    private static final UUID TO_LOCATION_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T01:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-19T02:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-19T03:00:00Z");

    @Mock
    private StockMoveRepository stockMoveRepository;

    @Mock
    private StockPickingRepository stockPickingRepository;

    @Mock
    private StockQuantRepository stockQuantRepository;

    @Mock
    private OutboundMovementEventPublisher eventPublisher;

    private CompleteOutboundMovementsUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new CompleteOutboundMovementsUsecase(
                stockMoveRepository, stockPickingRepository, stockQuantRepository, eventPublisher);
    }

    @Test
    void consumesReservedQuantAndCompletesMovementAndPickingExactlyOnce() {
        StockMove movement = movement(MoveState.ASSIGNED);
        StockPicking picking = picking(PickingState.ASSIGNED);
        StockMoveLine line = new StockMoveLine(UUID.randomUUID(), MOVE_ID, QUANT_ID, 4);
        StockQuant quant = new StockQuant(
                QUANT_ID,
                OWNER_ID,
                FROM_LOCATION_ID,
                "SKU-1",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2027, 8, 1),
                10,
                4,
                null);
        stubExecution(movement, picking);
        when(stockMoveRepository.findLinesOf(List.of(MOVE_ID))).thenReturn(List.of(line));
        when(stockQuantRepository.findByIds(any())).thenReturn(List.of(quant));

        var result = usecase.execute(command(List.of(MOVE_ID)));

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(quant.getOnHandQuantity()).isEqualTo(6);
        assertThat(quant.getReservedQuantity()).isZero();
        assertThat(movement.getState()).isEqualTo(MoveState.DONE);
        assertThat(picking.state()).isEqualTo(PickingState.DONE);
        verify(stockQuantRepository).save(quant);
        verify(stockMoveRepository).saveAll(List.of(movement));
        verify(stockPickingRepository).save(picking);
        verify(stockMoveRepository, never()).findByAllocationDemandId(any());

        ArgumentCaptor<OutboundMovementsCompleted> event = ArgumentCaptor.forClass(OutboundMovementsCompleted.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().allocationId()).isEqualTo(ALLOCATION_ID);
        assertThat(event.getValue().shipmentId()).isEqualTo(SHIPMENT_ID);
        assertThat(event.getValue().occurredAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void treatsACompletedExecutionAsAnIdempotentRetry() {
        StockMove movement = movement(MoveState.DONE);
        StockPicking picking = picking(PickingState.DONE);
        stubExecution(movement, picking);

        var result = usecase.execute(command(List.of(MOVE_ID)));

        assertThat(result.status()).isEqualTo(Status.ALREADY_COMPLETED);
        verify(stockMoveRepository, never()).findLinesOf(any());
        verify(stockQuantRepository, never()).findByIds(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void rejectsAPartialMovementListBeforeChangingStock() {
        StockMove movement = movement(MoveState.ASSIGNED);
        when(stockMoveRepository.findByPickingIds(List.of(ALLOCATION_ID))).thenReturn(List.of(movement));

        assertThatThrownBy(() -> usecase.execute(command(List.of(UUID.randomUUID()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match allocation");

        verify(stockPickingRepository, never()).findByIds(any());
        verify(stockQuantRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    private void stubExecution(StockMove movement, StockPicking picking) {
        when(stockMoveRepository.findByPickingIds(List.of(ALLOCATION_ID))).thenReturn(List.of(movement));
        when(stockPickingRepository.findByIds(any())).thenReturn(List.of(picking));
    }

    private static CompleteOutboundMovementsCommand command(List<UUID> movementIds) {
        return new CompleteOutboundMovementsCommand(ALLOCATION_ID, ORDER_ID, SHIPMENT_ID, movementIds, COMPLETED_AT);
    }

    private static StockMove movement(MoveState state) {
        return new StockMove(
                MOVE_ID,
                DEMAND_ID,
                OWNER_ID,
                "SKU-1",
                FROM_LOCATION_ID,
                TO_LOCATION_ID,
                ALLOCATION_ID,
                DEMAND_LINE_ID,
                ORDER_LINE_ID.toString(),
                ORDER_LINE_ID,
                4,
                state,
                CREATED_AT,
                ASSIGNED_AT,
                null);
    }

    private static StockPicking picking(PickingState state) {
        return new StockPicking(
                ALLOCATION_ID,
                PICKING_TYPE_ID,
                PickingDirection.OUTBOUND,
                OWNER_ID,
                ORDER_ID,
                FROM_LOCATION_ID,
                TO_LOCATION_ID,
                COMPLETED_AT.plusSeconds(3600),
                50,
                state,
                null);
    }
}
