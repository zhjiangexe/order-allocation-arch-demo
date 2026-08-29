package com.flowzati.archone.inventory.position.receipt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.location.application.repo.StockLocationStore;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.service.InboundReceiptCompleter;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("完成搬運")
class InboundReceiptCompleterTest {

    private static final String SKU = "SKU-1";
    private final Instant now = Instant.parse("2026-07-25T02:00:00Z");
    private final InboundReceiptCompleter.BatchIdentity batch =
            new InboundReceiptCompleter.BatchIdentity(StockFixtures.ARRIVED_ON, StockFixtures.EXPIRES_ON);

    private StockMoveStore stockMoveStore;
    private StockMoveLineStore stockMoveLineStore;
    private StockOperationStore stockOperationStore;
    private StockQuantStore stockQuantStore;
    private StockLocationStore stockLocationStore;
    private InboundReceiptCompleter completer;

    @BeforeEach
    void setUp() {
        stockMoveStore = mock(StockMoveStore.class);
        stockMoveLineStore = mock(StockMoveLineStore.class);
        stockOperationStore = mock(StockOperationStore.class);
        stockQuantStore = mock(StockQuantStore.class);
        stockLocationStore = mock(StockLocationStore.class);
        completer = new InboundReceiptCompleter(
                stockMoveStore, stockMoveLineStore, stockOperationStore, stockQuantStore, stockLocationStore);
        when(stockLocationStore.findById(InventoryFixtures.SUPPLIERS_LOCATION_ID))
                .thenReturn(Optional.of(InventoryFixtures.suppliersLocation()));
        when(stockLocationStore.findById(InventoryFixtures.LOCATION_ID))
                .thenReturn(Optional.of(InventoryFixtures.internalLocation()));
    }

    @Test
    @DisplayName("五維鍵命中既有列時應加到那一列，不另開新列")
    void shouldAddToTheExistingStockRowWhenAllFiveDimensionsMatch() {
        StockQuant existing = StockFixtures.unexpiredBatch(SKU, 4, 0);
        givenIdentityMatches(existing);
        StockMove incoming = inboundMove(10);

        completer.complete(List.of(incoming), batch, now);

        // 沒有「差不多就併進去」的規則，因為根本沒有規則要定——五個維度全等才是同一批。
        assertThat(existing.getOnHandQuantity()).isEqualTo(14);
        assertThat(savedPools()).containsExactly(existing);
    }

    @Test
    @DisplayName("五維鍵沒有命中時應新開一列，而**開的時候是空的**")
    void shouldOpenAnEmptyStockRowWhenNoIdentityMatches() {
        givenNoIdentityMatch();
        StockMove incoming = inboundMove(10);

        completer.complete(List.of(incoming), batch, now);

        // **這是這個 change 的核心。** 舊的補貨有兩條路：找到就加、找不到就用最終數量新建——
        // 而第二條正是繞過搬運的那一條。現在只有一條：開一列空的，再由明細把量放進去。
        StockQuant opened = savedPools().getFirst();
        assertThat(opened.getOwnerId()).isEqualTo(InventoryFixtures.OWNER_ID);
        assertThat(opened.getLocationId()).isEqualTo(InventoryFixtures.LOCATION_ID);
        assertThat(opened.getSkuCode()).isEqualTo(SKU);
        assertThat(opened.getInDate()).isEqualTo(StockFixtures.ARRIVED_ON);
        assertThat(opened.getExpiryDate()).isEqualTo(StockFixtures.EXPIRES_ON);
        assertThat(opened.getOnHandQuantity()).isEqualTo(10);
        assertThat(opened.getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("明細要指向貨真的落到的那一列庫存")
    void shouldWriteALineNamingTheStockRowTheGoodsLandedIn() {
        StockQuant existing = StockFixtures.unexpiredBatch(SKU, 0, 0);
        givenIdentityMatches(existing);
        StockMove incoming = inboundMove(10);

        completer.complete(List.of(incoming), batch, now);

        // 明細是庫存變動的憑證——少了它，那 10 件在系統裡就沒有來源。
        StockMoveLine line = savedLines().getFirst();
        assertThat(line.moveId()).isEqualTo(incoming.getId());
        assertThat(line.stockQuantId()).isEqualTo(existing.getId());
        assertThat(line.quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("完成之後搬運應為已完成，且說得出什麼時候拿到貨")
    void shouldLeaveTheMovementDone() {
        givenIdentityMatches(StockFixtures.unexpiredBatch(SKU, 0, 0));
        StockMove incoming = inboundMove(10);

        completer.complete(List.of(incoming), batch, now);

        // 收貨也走完整段 CONFIRMED → ASSIGNED → DONE。中間狀態在同一個交易內沒有人看得到，
        // 但狀態與時間戳的 CHECK 要求已完成的搬運說得出它什麼時候拿到貨。
        assertThat(incoming.getState()).isEqualTo(MoveState.DONE);
        assertThat(incoming.getAssignedAt()).isEqualTo(now);
        assertThat(savedMoves()).containsExactly(incoming);

        ArgumentCaptor<StockOperation> pickingCaptor = ArgumentCaptor.forClass(StockOperation.class);
        verify(stockOperationStore).save(pickingCaptor.capture());
        assertThat(pickingCaptor.getValue().state()).isEqualTo(StockOperationState.DONE);
    }

    @Test
    @DisplayName("同一次完成裡兩段同 SKU 的搬運應落在同一列，不得各開一列")
    void shouldLandTwoMovementsOfTheSameSkuInOneRow() {
        givenNoIdentityMatch();
        UUID stockOperationId = IdGenerator.nextId();
        StockMove first = inboundMove(10, stockOperationId);
        StockMove second = inboundMove(5, stockOperationId);

        completer.complete(List.of(first, second), batch, now);

        // 各開一列的話兩列的五維完全相同，接著撞 uq_stock_pools_batch——而那是在寫入時才炸，
        // 訊息會進 DLT 而不是留下一個看得懂的錯。
        assertThat(savedPools()).hasSize(1);
        assertThat(savedPools().getFirst().getOnHandQuantity()).isEqualTo(15);
        assertThat(savedLines()).hasSize(2);
        verify(stockLocationStore).findById(InventoryFixtures.SUPPLIERS_LOCATION_ID);
        verify(stockLocationStore).findById(InventoryFixtures.LOCATION_ID);
        verify(stockQuantStore)
                .findByIdentity(
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.LOCATION_ID,
                        SKU,
                        StockFixtures.ARRIVED_ON,
                        StockFixtures.EXPIRES_ON);
    }

    @Test
    @DisplayName("operation 遺失時應在改變 movement 與庫存前拒絕完成")
    void shouldRejectMissingPickingBeforeChangingStock() {
        StockMove incoming = inboundMove(10);
        when(stockOperationStore.findByIds(java.util.Set.of(incoming.getStockOperationId())))
                .thenReturn(List.of());

        assertThatThrownBy(() -> completer.complete(List.of(incoming), batch, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stock operations no longer exist");

        assertThat(incoming.getState()).isEqualTo(MoveState.CONFIRMED);
        verify(stockQuantStore, never())
                .findByIdentity(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        verify(stockQuantStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("出貨方向的搬運不得被完成——那一半屬 R7")
    void shouldRefuseToCompleteAnOutgoingMovement() {
        StockMove outgoing = InventoryFixtures.assignedMove(IdGenerator.nextId(), SKU, IdGenerator.nextId(), 5, now);
        when(stockLocationStore.findById(InventoryFixtures.CUSTOMERS_LOCATION_ID))
                .thenReturn(Optional.of(InventoryFixtures.customersLocation()));

        // 留一個沒有呼叫端、沒有測試的分支比沒有它更糟——它腐爛的方式是「看起來能用」。
        assertThatThrownBy(() -> completer.complete(List.of(outgoing), batch, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not implemented until shipping exists");

        verify(stockMoveStore, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    private void givenIdentityMatches(StockQuant pool) {
        when(stockQuantStore.findByIdentity(
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.LOCATION_ID,
                        SKU,
                        StockFixtures.ARRIVED_ON,
                        StockFixtures.EXPIRES_ON))
                .thenReturn(Optional.of(pool));
    }

    private void givenNoIdentityMatch() {
        when(stockQuantStore.findByIdentity(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
    }

    /** 一段還在等貨的入庫搬運：供應商 → 庫存位置，沒有訂單行。 */
    private StockMove inboundMove(int quantity) {
        return inboundMove(quantity, IdGenerator.nextId());
    }

    private StockMove inboundMove(int quantity, UUID stockOperationId) {
        StockOperation operation = StockOperation.confirmedInbound(
                stockOperationId,
                InventoryFixtures.INBOUND_TYPE_ID,
                InventoryFixtures.OWNER_ID,
                InventoryFixtures.SUPPLIERS_LOCATION_ID,
                InventoryFixtures.LOCATION_ID);
        when(stockOperationStore.findByIds(java.util.Set.of(stockOperationId))).thenReturn(List.of(operation));
        return StockMove.confirmed(
                IdGenerator.nextId(),
                stockOperationId,
                InventoryFixtures.OWNER_ID,
                SKU,
                InventoryFixtures.SUPPLIERS_LOCATION_ID,
                InventoryFixtures.LOCATION_ID,
                quantity,
                now.minusSeconds(1));
    }

    private List<StockQuant> savedPools() {
        ArgumentCaptor<StockQuant> captor = ArgumentCaptor.forClass(StockQuant.class);
        verify(stockQuantStore, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<StockMove> savedMoves() {
        ArgumentCaptor<Collection<StockMove>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(stockMoveStore).saveAll(captor.capture());
        return List.copyOf(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private List<StockMoveLine> savedLines() {
        ArgumentCaptor<Collection<StockMoveLine>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(stockMoveLineStore).saveAll(captor.capture());
        return List.copyOf(captor.getValue());
    }
}
