package com.flowzati.archone.inventory.movement.application;

import com.flowzati.archone.catalog.domain.aggregate.Facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.catalog.domain.aggregate.StockLocation;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.inventory.movement.domain.type.MoveState;
import com.flowzati.archone.inventory.movement.domain.type.PickingState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
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
  private StockLocationRepository stockLocationRepository;
  private PickingTypeRepository pickingTypeRepository;
  private StockPickingRepository stockPickingRepository;
  private StockMoveRepository stockMoveRepository;
  private InboundReceiptRegistrar registrar;

  @BeforeEach
  void setUp() {
    stockLocationRepository = mock(StockLocationRepository.class);
    pickingTypeRepository = mock(PickingTypeRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    registrar = new InboundReceiptRegistrar(
        stockLocationRepository, pickingTypeRepository, stockPickingRepository,
        stockMoveRepository);
  }

  @Test
  @DisplayName("入庫建立供應商到內部位置的 picking/move，且不帶 allocation demand reference")
  void shouldRecordSupplyOnlyInboundExecution() {
    UUID selectedLocationId = UUID.randomUUID();
    given(stockLocationRepository.findById(selectedLocationId))
        .willReturn(Optional.of(StockLocation.internal(
            selectedLocationId, OrderFixtures.FACILITY_ID, "WH-TEST/Stock-B", "測試倉／B 區")));
    given(pickingTypeRepository.find(OrderFixtures.FACILITY_ID, PickingDirection.INBOUND))
        .willReturn(Optional.of(MovementFixtures.inboundType()));
    given(stockMoveRepository.saveAll(org.mockito.ArgumentMatchers.any()))
        .willAnswer(invocation -> List.copyOf(invocation.getArgument(0, Collection.class)));

    List<StockMove> recorded = registrar.register(
        OrderFixtures.FACILITY_ID, OrderFixtures.OWNER_ID, selectedLocationId,
        "SKU-1", 8, now);

    ArgumentCaptor<StockPicking> pickingCaptor = ArgumentCaptor.forClass(StockPicking.class);
    then(stockPickingRepository).should().save(pickingCaptor.capture());
    StockPicking picking = pickingCaptor.getValue();
    assertThat(picking.direction()).isEqualTo(PickingDirection.INBOUND);
    assertThat(picking.orderId()).isNull();
    assertThat(picking.toLocationId()).isEqualTo(selectedLocationId);
    assertThat(picking.state()).isEqualTo(PickingState.CONFIRMED);

    StockMove move = recorded.getFirst();
    assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
    assertThat(move.getOrderLineId()).isNull();
    assertThat(move.getAllocationDemandId()).isNull();
    assertThat(move.getAllocationDemandLineId()).isNull();
    assertThat(move.getSourceLineId()).isNull();
  }

  @Test
  @DisplayName("Facility 沒有入庫作業類型時不得留下半成品")
  void shouldFailWhenTheFacilityHasNoInboundOperationType() {
    given(stockLocationRepository.findById(OrderFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(OrderFixtures.FACILITY_ID, PickingDirection.INBOUND))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> registrar.register(
        OrderFixtures.FACILITY_ID, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID,
        "SKU-1", 8, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no inbound operation type");

    verifyNoInteractions(stockPickingRepository, stockMoveRepository);
  }
}
