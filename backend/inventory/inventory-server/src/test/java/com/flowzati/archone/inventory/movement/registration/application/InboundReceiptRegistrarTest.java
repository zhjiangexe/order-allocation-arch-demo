package com.flowzati.archone.inventory.movement.registration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowzati.archone.inventory.location.application.store.StockLocationStore;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.movement.application.service.InboundReceiptRegistrar;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
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

@DisplayName("建立 supply-only inbound 搬運")
class InboundReceiptRegistrarTest {

    private final Instant now = Instant.parse("2026-07-21T23:00:00Z");
    private StockLocationStore stockLocationStore;
    private StockOperationTypeStore stockOperationTypeStore;
    private StockOperationStore stockOperationStore;
    private StockMoveStore stockMoveStore;
    private InboundReceiptRegistrar registrar;

    @BeforeEach
    void setUp() {
        stockLocationStore = mock(StockLocationStore.class);
        stockOperationTypeStore = mock(StockOperationTypeStore.class);
        stockOperationStore = mock(StockOperationStore.class);
        stockMoveStore = mock(StockMoveStore.class);
        registrar = new InboundReceiptRegistrar(
                stockLocationStore, stockOperationTypeStore, stockOperationStore, stockMoveStore);
    }

    @Test
    @DisplayName("入庫建立供應商到內部位置的 operation/move，且不帶 source document reference")
    void shouldRecordSupplyOnlyInboundExecution() {
        UUID selectedLocationId = UUID.randomUUID();
        given(stockLocationStore.findById(selectedLocationId))
                .willReturn(Optional.of(StockLocation.internal(
                        selectedLocationId, InventoryFixtures.FACILITY_ID, "WH-TEST/Stock-B", "測試倉／B 區")));
        given(stockOperationTypeStore.find(InventoryFixtures.FACILITY_ID, StockOperationDirection.INBOUND))
                .willReturn(Optional.of(InventoryFixtures.inboundType()));
        given(stockMoveStore.saveAll(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> List.copyOf(invocation.getArgument(0, Collection.class)));

        List<StockMove> recorded = registrar.register(
                InventoryFixtures.FACILITY_ID, InventoryFixtures.OWNER_ID, selectedLocationId, "SKU-1", 8, now);

        ArgumentCaptor<StockOperation> pickingCaptor = ArgumentCaptor.forClass(StockOperation.class);
        then(stockOperationStore).should().save(pickingCaptor.capture());
        StockOperation operation = pickingCaptor.getValue();
        assertThat(operation.direction()).isEqualTo(StockOperationDirection.INBOUND);
        assertThat(operation.source()).isNull();
        assertThat(operation.toLocationId()).isEqualTo(selectedLocationId);
        assertThat(operation.state()).isEqualTo(StockOperationState.CONFIRMED);

        StockMove move = recorded.getFirst();
        assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
        assertThat(move.getSourceLineId()).isNull();
    }

    @Test
    @DisplayName("Facility 沒有入庫作業類型時不得留下半成品")
    void shouldFailWhenTheFacilityHasNoInboundOperationType() {
        given(stockLocationStore.findById(InventoryFixtures.LOCATION_ID))
                .willReturn(Optional.of(InventoryFixtures.internalLocation()));
        given(stockOperationTypeStore.find(InventoryFixtures.FACILITY_ID, StockOperationDirection.INBOUND))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> registrar.register(
                        InventoryFixtures.FACILITY_ID,
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.LOCATION_ID,
                        "SKU-1",
                        8,
                        now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no inbound operation type");

        verifyNoInteractions(stockOperationStore, stockMoveStore);
    }
}
