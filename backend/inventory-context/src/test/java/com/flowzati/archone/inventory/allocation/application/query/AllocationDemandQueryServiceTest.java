package com.flowzati.archone.inventory.allocation.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import com.flowzati.archone.inventory.balance.domain.valueobject.AllocatableBatches;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationDemandQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T08:00:00Z");

    private AllocationDemandRepository demandRepository;
    private StockQuantRepository stockQuantRepository;
    private StockMoveRepository stockMoveRepository;
    private StockPickingRepository stockPickingRepository;
    private AllocationDemandQueryService queryService;

    @BeforeEach
    void setUp() {
        demandRepository = mock(AllocationDemandRepository.class);
        stockQuantRepository = mock(StockQuantRepository.class);
        stockMoveRepository = mock(StockMoveRepository.class);
        stockPickingRepository = mock(StockPickingRepository.class);
        BusinessClock clock = InventoryFixtures.businessClock(Clock.fixed(NOW, ZoneOffset.UTC), "Asia/Taipei");
        queryService = new AllocationDemandQueryService(
                demandRepository, stockQuantRepository, stockMoveRepository, stockPickingRepository, clock);

        when(stockMoveRepository.findByAllocationDemandId(any())).thenReturn(List.of());
        when(stockMoveRepository.findLinesOf(List.of())).thenReturn(List.of());
        when(stockQuantRepository.findByIds(List.of())).thenReturn(List.of());
        when(stockPickingRepository.findByIds(List.of())).thenReturn(List.of());
    }

    @Test
    @DisplayName("共享 SKU 的後來 demand 應先解釋為 FIFO 阻塞，並同時呈現當下 ATP 缺口")
    void shouldExplainFifoBlockerAndCurrentShortage() {
        AllocationDemand first = pendingDemand(1, NOW, 5);
        AllocationDemand second = pendingDemand(2, NOW.plusSeconds(1), 5);
        when(demandRepository.findPending(2)).thenReturn(List.of(first, second));
        when(stockQuantRepository.findAllocatableBatchesBySku(
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.LOCATION_ID,
                        java.util.Set.of("SKU-1"),
                        StockFixtures.TODAY))
                .thenReturn(AllocatableBatches.of(
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.LOCATION_ID,
                        Map.of("SKU-1", List.of(StockFixtures.unexpiredBatch("SKU-1", 4, 0)))));

        List<AllocationDemandView> result = queryService.listPending(2);

        assertThat(result.getFirst().waitingReason()).isEqualTo(AllocationWaitingReason.INSUFFICIENT_ATP);
        assertThat(result.getFirst().missingQuantities()).containsEntry("SKU-1", 1);
        assertThat(result.get(1).waitingReason()).isEqualTo(AllocationWaitingReason.WAITING_FOR_EARLIER_DEMAND);
        assertThat(result.get(1).blockedByAllocationDemandId()).isEqualTo(first.id());
        assertThat(result.get(1).availableQuantities()).containsEntry("SKU-1", 4);
        assertThat(result.get(1).missingQuantities()).containsEntry("SKU-1", 1);
    }

    @Test
    @DisplayName("allocated demand 應回傳 move、picking 與實際預留批次的 FEFO 身分")
    void shouldShowReservedStockQuantIdentity() {
        AllocationDemand demand = pendingDemand(3, NOW, 5);
        demand.markAllocated();
        UUID orderId = UUID.fromString(demand.source().sourceId());
        UUID pickingId = new UUID(3L, 1L);
        UUID moveId = new UUID(3L, 2L);
        UUID stockQuantId = new UUID(3L, 3L);
        StockPicking picking = StockPicking.confirmedDemand(
                pickingId,
                InventoryFixtures.OUTBOUND_TYPE_ID,
                PickingDirection.OUTBOUND,
                InventoryFixtures.OWNER_ID,
                orderId,
                InventoryFixtures.LOCATION_ID,
                InventoryFixtures.CUSTOMERS_LOCATION_ID,
                InventoryFixtures.DISPATCH_BY,
                InventoryFixtures.RELEASE_PRIORITY);
        picking.assign();
        StockMove move = StockMove.confirmedForDemand(
                moveId,
                pickingId,
                InventoryFixtures.OWNER_ID,
                "SKU-1",
                InventoryFixtures.LOCATION_ID,
                InventoryFixtures.CUSTOMERS_LOCATION_ID,
                demand.id(),
                demand.lines().getFirst().id(),
                demand.lines().getFirst().sourceLineId(),
                orderId,
                5,
                NOW);
        move.assign(NOW.plusSeconds(1));
        StockMoveLine moveLine = new StockMoveLine(new UUID(3L, 4L), moveId, stockQuantId, 5);
        var stockQuant = StockFixtures.unexpiredBatch(stockQuantId, "SKU-1", 10, 5);
        when(demandRepository.findBySource(SourceAllocationUnit.primaryOrder(orderId.toString())))
                .thenReturn(java.util.Optional.of(demand));
        when(stockMoveRepository.findByAllocationDemandId(demand.id())).thenReturn(List.of(move));
        when(stockMoveRepository.findLinesOf(List.of(moveId))).thenReturn(List.of(moveLine));
        when(stockQuantRepository.findByIds(List.of(stockQuantId))).thenReturn(List.of(stockQuant));
        when(stockPickingRepository.findByIds(List.of(pickingId))).thenReturn(List.of(picking));

        AllocationDemandView result = queryService.findPrimaryOrder(orderId).orElseThrow();

        assertThat(result.waitingReason()).isNull();
        assertThat(result.pickings()).extracting(AllocationPickingView::state).containsExactly("ASSIGNED");
        assertThat(result.moves()).extracting(AllocationMoveView::state).containsExactly("ASSIGNED");
        assertThat(result.moves().getFirst().reservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.stockQuantId()).isEqualTo(stockQuantId);
            assertThat(reservation.skuCode()).isEqualTo("SKU-1");
            assertThat(reservation.inDate()).isEqualTo(StockFixtures.ARRIVED_ON);
            assertThat(reservation.expiryDate()).isEqualTo(StockFixtures.EXPIRES_ON);
            assertThat(reservation.quantity()).isEqualTo(5);
        });
    }

    private static AllocationDemand pendingDemand(long seed, Instant enqueuedAt, int quantity) {
        UUID demandId = new UUID(0L, seed);
        return AllocationDemand.accept(
                demandId,
                SourceAllocationUnit.primaryOrder(new UUID(1L, seed).toString()),
                InventoryFixtures.OWNER_ID,
                InventoryFixtures.FACILITY_ID,
                InventoryFixtures.LOCATION_ID,
                InventoryFixtures.DISPATCH_BY,
                InventoryFixtures.RELEASE_PRIORITY,
                enqueuedAt,
                List.of(new AllocationDemandLineRequest("line-" + seed, "SKU-1", quantity)),
                () -> new UUID(2L, seed));
    }
}
