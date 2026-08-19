package com.flowzati.archone.inventory.allocation.application.service.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.event.AllocationCommitted;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationBatchPick;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandPlan;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AllocationCommitter")
class AllocationCommitterTest {

    private static final UUID OWNER_ID = uuid(1);
    private static final UUID FACILITY_ID = uuid(2);
    private static final UUID LOCATION_ID = uuid(3);
    private static final UUID CUSTOMER_LOCATION_ID = uuid(4);
    private static final UUID DEMAND_ID = uuid(10);
    private static final UUID PICKING_ID = uuid(11);
    private static final Instant NOW = Instant.parse("2026-08-18T04:00:00Z");

    private AllocationDemandRepository demandRepository;
    private StockQuantRepository poolRepository;
    private StockMoveRepository moveRepository;
    private StockPickingRepository pickingRepository;
    private AllocationCommitter committer;

    @BeforeEach
    void setUp() {
        demandRepository = mock(AllocationDemandRepository.class);
        poolRepository = mock(StockQuantRepository.class);
        moveRepository = mock(StockMoveRepository.class);
        pickingRepository = mock(StockPickingRepository.class);
        committer = new AllocationCommitter(
                demandRepository, poolRepository, moveRepository, pickingRepository, () -> uuid(99));
    }

    @Test
    @DisplayName("同一 transaction 套用 stock、move lines、picking 與 demand 狀態")
    void shouldCommitAllAllocationStateAndBuildGenericFact() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();
        StockQuant pool = pool(20, LOCATION_ID);
        StockMove move = move(line);
        StockPicking picking = picking();
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), pool.getId(), 5)));
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        when(poolRepository.findByIds(anyCollection())).thenReturn(List.of(pool));
        when(moveRepository.findByAllocationDemandId(DEMAND_ID)).thenReturn(List.of(move));
        when(pickingRepository.findByIds(java.util.Set.of(PICKING_ID))).thenReturn(List.of(picking));

        AllocationCommitted fact = committer.commit(plan, NOW).orElseThrow();

        assertThat(pool.getReservedQuantity()).isEqualTo(5);
        assertThat(move.getState().name()).isEqualTo("ASSIGNED");
        assertThat(picking.state().name()).isEqualTo("ASSIGNED");
        assertThat(demand.status()).isEqualTo(AllocationDemandStatus.ALLOCATED);
        assertThat(fact.source()).isEqualTo(demand.source());
        assertThat(fact.moves().getFirst().allocationDemandLineId()).isEqualTo(line.id());
        assertThat(fact.moves().getFirst().sourceLineId()).isEqualTo("source-line-1");
        verify(poolRepository).save(pool);
        verify(moveRepository).saveLines(any());
        verify(demandRepository).save(demand);
    }

    @Test
    @DisplayName("stale plan 的庫位不一致時不修改任何 aggregate")
    void shouldRejectLocationMismatchBeforeMutation() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();
        StockQuant wrongLocation = pool(30, uuid(300));
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), wrongLocation.getId(), 5)));
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        when(poolRepository.findByIds(anyCollection())).thenReturn(List.of(wrongLocation));
        when(moveRepository.findByAllocationDemandId(DEMAND_ID)).thenReturn(List.of(move(line)));

        assertThatThrownBy(() -> committer.commit(plan, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scopes do not match");

        assertThat(wrongLocation.getReservedQuantity()).isZero();
        assertThat(demand.status()).isEqualTo(AllocationDemandStatus.PENDING);
        verify(poolRepository, never()).save(any());
        verify(moveRepository, never()).saveLines(any());
    }

    @Test
    @DisplayName("plan 指向的 stock quant 已消失時，在任何 aggregate mutation 前拒絕")
    void shouldRejectMissingStockQuantBeforeMutation() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();
        StockMove move = move(line);
        UUID missingPoolId = uuid(404);
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), missingPoolId, 5)));
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        when(moveRepository.findByAllocationDemandId(DEMAND_ID)).thenReturn(List.of(move));
        when(poolRepository.findByIds(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> committer.commit(plan, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Planned stock quants no longer exist")
                .hasMessageContaining(missingPoolId.toString());

        assertThat(move.getState().name()).isEqualTo("CONFIRMED");
        assertThat(demand.status()).isEqualTo(AllocationDemandStatus.PENDING);
        verify(poolRepository, never()).save(any());
        verify(moveRepository, never()).saveLines(any());
    }

    @Test
    @DisplayName("move source location 與 accepted demand 不一致時在 reserve 前拒絕")
    void shouldRejectExecutionLocationMismatchBeforeReservation() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();
        StockMove wrongMove = StockMove.confirmedForDemand(
                uuid(41),
                PICKING_ID,
                OWNER_ID,
                line.skuCode(),
                uuid(301),
                CUSTOMER_LOCATION_ID,
                DEMAND_ID,
                line.id(),
                line.sourceLineId(),
                null,
                line.quantity(),
                Instant.parse("2026-08-18T00:00:00Z"));
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), uuid(20), 5)));
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        when(moveRepository.findByAllocationDemandId(DEMAND_ID)).thenReturn(List.of(wrongMove));

        assertThatThrownBy(() -> committer.commit(plan, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution differs");

        verify(poolRepository, never()).save(any());
    }

    @Test
    @DisplayName("picking scope 不一致時在 reserve 前拒絕")
    void shouldRejectPickingScopeMismatchBeforeReservation() {
        AllocationDemand demand = demand();
        AllocationDemandLine line = demand.lines().getFirst();
        StockQuant pool = pool(20, LOCATION_ID);
        StockMove move = move(line);
        StockPicking wrongPicking = new StockPicking(
                PICKING_ID,
                uuid(50),
                PickingDirection.OUTBOUND,
                OWNER_ID,
                null,
                uuid(301),
                CUSTOMER_LOCATION_ID,
                Instant.parse("2026-08-20T00:00:00Z"),
                50,
                com.flowzati.archone.inventory.movement.domain.type.PickingState.CONFIRMED,
                0L);
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), pool.getId(), 5)));
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        when(moveRepository.findByAllocationDemandId(DEMAND_ID)).thenReturn(List.of(move));
        when(poolRepository.findByIds(anyCollection())).thenReturn(List.of(pool));
        when(pickingRepository.findByIds(java.util.Set.of(PICKING_ID))).thenReturn(List.of(wrongPicking));

        assertThatThrownBy(() -> committer.commit(plan, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different source locations");

        assertThat(pool.getReservedQuantity()).isZero();
        assertThat(move.getState().name()).isEqualTo("CONFIRMED");
        verify(poolRepository, never()).save(any());
        verify(moveRepository, never()).saveLines(any());
    }

    @Test
    @DisplayName("已配置 demand 的 retry 是 no-op 且不重發 completion")
    void shouldNotCommitAllocatedDemandTwice() {
        AllocationDemand demand = demand();
        demand.markAllocated();
        when(demandRepository.findById(DEMAND_ID)).thenReturn(Optional.of(demand));
        AllocationDemandLine line = demand.lines().getFirst();
        AllocationDemandPlan plan = AllocationDemandPlan.readyToCommit(
                demand, List.of(new AllocationBatchPick(DEMAND_ID, line.id(), uuid(20), 5)));

        assertThat(committer.commit(plan, NOW)).isEmpty();
        verify(poolRepository, never()).findByIds(any());
        verify(moveRepository, never()).findByAllocationDemandId(any());
    }

    private static AllocationDemand demand() {
        return AllocationDemand.accept(
                DEMAND_ID,
                new SourceAllocationUnit(AllocationSourceType.TRANSFER, "transfer-1", "LEG-A"),
                OWNER_ID,
                FACILITY_ID,
                LOCATION_ID,
                Instant.parse("2026-08-20T00:00:00Z"),
                50,
                Instant.parse("2026-08-18T00:00:00Z"),
                List.of(new AllocationDemandLineRequest("source-line-1", "SKU-A", 5)),
                () -> uuid(12));
    }

    private static StockQuant pool(int id, UUID locationId) {
        return new StockQuant(
                uuid(id),
                OWNER_ID,
                locationId,
                "SKU-A",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 12, 31),
                10,
                0,
                0L);
    }

    private static StockMove move(AllocationDemandLine line) {
        return StockMove.confirmedForDemand(
                uuid(40),
                PICKING_ID,
                OWNER_ID,
                line.skuCode(),
                LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                DEMAND_ID,
                line.id(),
                line.sourceLineId(),
                null,
                line.quantity(),
                Instant.parse("2026-08-18T00:00:00Z"));
    }

    private static StockPicking picking() {
        return new StockPicking(
                PICKING_ID,
                uuid(50),
                PickingDirection.OUTBOUND,
                OWNER_ID,
                null,
                LOCATION_ID,
                CUSTOMER_LOCATION_ID,
                Instant.parse("2026-08-20T00:00:00Z"),
                50,
                com.flowzati.archone.inventory.movement.domain.type.PickingState.CONFIRMED,
                0L);
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
