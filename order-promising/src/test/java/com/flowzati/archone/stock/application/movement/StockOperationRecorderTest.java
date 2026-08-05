package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.MoveState;
import com.flowzati.archone.stock.domain.model.PickingState;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("建立搬運")
class StockOperationRecorderTest {

  private final Instant now = Instant.parse("2026-07-21T23:00:00Z");

  private StockLocationRepository stockLocationRepository;
  private PickingTypeRepository pickingTypeRepository;
  private StockPickingRepository stockPickingRepository;
  private StockMoveRepository stockMoveRepository;
  private StockOperationRecorder recorder;

  @BeforeEach
  void setUp() {
    stockLocationRepository = mock(StockLocationRepository.class);
    pickingTypeRepository = mock(PickingTypeRepository.class);
    stockPickingRepository = mock(StockPickingRepository.class);
    stockMoveRepository = mock(StockMoveRepository.class);
    recorder = new StockOperationRecorder(
        stockLocationRepository, pickingTypeRepository, stockPickingRepository,
        stockMoveRepository);
  }

  @Test
  @DisplayName("一件貨都沒有時仍要建搬運，狀態是還在等貨")
  void shouldRecordAMovementWithoutConsultingStockAtAll() {
    Demand demand = demand("SKU-1", 5);
    givenAnOutboundOperationType();

    List<StockMove> recorded = recorder.recordOutbound(demand, now);

    // **這裡沒有任何庫存的協作者可以被查詢**——建立與配貨是兩個動作，而這正是入庫能重用
    // 這個元件的理由：入庫沒有可承諾量的問題。
    StockMove move = recorded.getFirst();
    assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
    assertThat(move.getCreatedAt()).isEqualTo(now);
    assertThat(move.getAssignedAt()).isNull();
    assertThat(move.getSkuCode()).isEqualTo("SKU-1");
    assertThat(move.getDemandQuantity()).isEqualTo(5);
    assertThat(move.getOrderLineId()).isEqualTo(demand.lines().getFirst().orderLineId());
  }

  @Test
  @DisplayName("回傳的搬運就是寫進去的那些——呼叫端不必再讀一次")
  void shouldHandBackExactlyWhatItWrote() {
    Demand demand = demand("SKU-1", 5);
    givenAnOutboundOperationType();

    List<StockMove> recorded = recorder.recordOutbound(demand, now);

    // 少了這條，回傳值可能是「另外造的一份」而測不出來，而下一步（鎖定）會拿著一組
    // **沒有被寫入**的搬運去轉狀態——寫回去時變成新增，同一條行就有了兩段搬運。
    assertThat(recorded).containsExactlyElementsOf(savedMoves());
  }

  @Test
  @DisplayName("起訖取自作業類型的預設值：庫存位置 → 客戶")
  void shouldTakeBothEndsFromTheOperationType() {
    Demand demand = demand("SKU-1", 5);
    givenAnOutboundOperationType();

    List<StockMove> recorded = recorder.recordOutbound(demand, now);

    // 終點是虛擬的客戶位置——出庫的目的地本來就在公司之外，這正是搬運不能沿用庫存那條
    // 「只能指向內部位置」約束的理由。
    ArgumentCaptor<StockPicking> captor = ArgumentCaptor.forClass(StockPicking.class);
    then(stockPickingRepository).should().save(captor.capture());
    StockPicking picking = captor.getValue();
    assertThat(picking.orderId()).isEqualTo(demand.orderId());
    assertThat(picking.pickingTypeId()).isEqualTo(MovementFixtures.OUTBOUND_TYPE_ID);
    assertThat(picking.fromLocationId()).isEqualTo(DemandFixtures.LOCATION_ID);
    assertThat(picking.toLocationId()).isEqualTo(MovementFixtures.CUSTOMERS_LOCATION_ID);
    assertThat(picking.state()).isEqualTo(PickingState.CONFIRMED);

    StockMove move = recorded.getFirst();
    assertThat(move.getPickingId()).isEqualTo(picking.id());
    assertThat(move.getFromLocationId()).isEqualTo(DemandFixtures.LOCATION_ID);
    assertThat(move.getToLocationId()).isEqualTo(MovementFixtures.CUSTOMERS_LOCATION_ID);
  }

  @Test
  @DisplayName("每一條行各一段搬運，全部掛在同一張作業單下")
  void shouldRecordOneMovementPerLineUnderOnePicking() {
    Demand demand = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(), DemandFixtures.line("SKU-1", 3), DemandFixtures.line("SKU-2", 4));
    givenAnOutboundOperationType();

    List<StockMove> recorded = recorder.recordOutbound(demand, now);

    // 一張單就是一份工作，而 ship-complete 判斷的單位正是一份工作——同一張單的行分散到多張
    // 作業單，整籃判斷就沒有分組鍵可用。
    assertThat(recorded).hasSize(2);
    assertThat(recorded.getFirst().getPickingId()).isNotNull();
    assertThat(recorded).extracting(StockMove::getPickingId).containsOnly(
        recorded.getFirst().getPickingId());
    assertThat(recorded).extracting(StockMove::getSkuCode).containsExactly("SKU-1", "SKU-2");
  }

  @Test
  @DisplayName("不同訂單各自建一張 outbound picking，不得共用")
  void shouldNotShareAnOutboundPickingAcrossOrders() {
    Demand first = demand("SKU-1", 3);
    Demand second = demand("SKU-1", 4);
    givenAnOutboundOperationType();

    List<StockMove> firstMoves = recorder.recordOutbound(first, now);
    List<StockMove> secondMoves = recorder.recordOutbound(second, now);

    assertThat(firstMoves.getFirst().getPickingId()).isNotNull();
    assertThat(secondMoves.getFirst().getPickingId()).isNotNull();
    assertThat(firstMoves.getFirst().getPickingId())
        .isNotEqualTo(secondMoves.getFirst().getPickingId());
  }

  @Test
  @DisplayName("一段式收貨建立供應商到內部位置的 inbound picking 與 move")
  void shouldRecordAnInboundPickingAndMovement() {
    UUID selectedLocationId = UUID.randomUUID();
    given(stockLocationRepository.findById(selectedLocationId))
        .willReturn(Optional.of(StockLocation.internal(
            selectedLocationId, OrderFixtures.FACILITY_ID, "WH-TEST/Stock-B", "測試倉／B 區")));
    given(pickingTypeRepository.find(OrderFixtures.FACILITY_ID, PickingDirection.INBOUND))
        .willReturn(Optional.of(MovementFixtures.inboundType()));
    given(stockMoveRepository.saveAll(org.mockito.ArgumentMatchers.any()))
        .willAnswer(invocation -> List.copyOf(invocation.getArgument(0, Collection.class)));

    List<StockMove> recorded = recorder.recordInbound(
        OrderFixtures.FACILITY_ID, OrderFixtures.OWNER_ID, selectedLocationId,
        "SKU-1", 8, now);

    ArgumentCaptor<StockPicking> pickingCaptor = ArgumentCaptor.forClass(StockPicking.class);
    then(stockPickingRepository).should().save(pickingCaptor.capture());
    StockPicking picking = pickingCaptor.getValue();
    assertThat(picking.pickingTypeId()).isEqualTo(MovementFixtures.INBOUND_TYPE_ID);
    assertThat(picking.ownerId()).isEqualTo(OrderFixtures.OWNER_ID);
    assertThat(picking.orderId()).isNull();
    assertThat(picking.fromLocationId()).isEqualTo(MovementFixtures.SUPPLIERS_LOCATION_ID);
    // PickingType 的 defaultTo 是 LOCATION_ID；呼叫者選的另一個庫位必須覆蓋預設值。
    assertThat(selectedLocationId).isNotEqualTo(MovementFixtures.inboundType().defaultToLocationId());
    assertThat(picking.toLocationId()).isEqualTo(selectedLocationId);
    assertThat(picking.state()).isEqualTo(PickingState.CONFIRMED);

    StockMove move = recorded.getFirst();
    assertThat(move.getPickingId()).isEqualTo(picking.id());
    assertThat(move.getOrderLineId()).isNull();
    assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
    assertThat(move.getSkuCode()).isEqualTo("SKU-1");
    assertThat(move.getDemandQuantity()).isEqualTo(8);
    assertThat(move.getToLocationId()).isEqualTo(selectedLocationId);
  }

  @Test
  @DisplayName("Facility 沒有入庫作業類型時不得記錄收貨")
  void shouldFailWhenTheFacilityHasNoInboundOperationType() {
    given(stockLocationRepository.findById(OrderFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(OrderFixtures.FACILITY_ID, PickingDirection.INBOUND))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> recorder.recordInbound(
        OrderFixtures.FACILITY_ID, OrderFixtures.OWNER_ID, OrderFixtures.LOCATION_ID,
        "SKU-1", 8, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no inbound operation type");

    verifyNoInteractions(stockPickingRepository, stockMoveRepository);
  }

  @Test
  @DisplayName("Facility 沒有出庫作業類型時應拋錯，不得靜默收下")
  void shouldFailWhenTheFacilityHasNoOutboundOperationType() {
    Demand demand = demand("SKU-1", 5);
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(StockFixtures.FACILITY_ID, PickingDirection.OUTBOUND))
        .willReturn(Optional.empty());

    // 「收下卻不記」會讓這張單的需求消失得無聲無息：它不會出現在任何佇列裡，因為佇列只
    // 回答「還在等貨的搬運」。大聲失敗才看得見設定漏了。
    assertThatThrownBy(() -> recorder.recordOutbound(demand, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has no outbound operation type");

    // 失敗發生在任何寫入之前——半張作業單比沒有作業單更難查。
    verifyNoInteractions(stockPickingRepository, stockMoveRepository);
  }

  @Test
  @DisplayName("需求指定的 Facility 與庫存位置所屬 Facility 不一致時應拒絕")
  void shouldRejectAMismatchedFacilityAndLocation() {
    Demand demand = demand("SKU-1", 5);
    StockLocation locationAtAnotherFacility = StockLocation.internal(
        DemandFixtures.LOCATION_ID,
        OrderFixtures.OTHER_FACILITY_ID,
        "WH-OTHER/Stock",
        "其他設施／庫存");
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.of(locationAtAnotherFacility));

    assertThatThrownBy(() -> recorder.recordOutbound(demand, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong to facility");

    verifyNoInteractions(pickingTypeRepository, stockPickingRepository, stockMoveRepository);
  }

  @Test
  @DisplayName("位置已不存在時應拋錯——搬運的起點不能是一個查不到的地方")
  void shouldFailWhenTheLocationNoLongerExists() {
    Demand demand = demand("SKU-1", 5);
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> recorder.recordOutbound(demand, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer exists");

    verifyNoInteractions(pickingTypeRepository, stockPickingRepository, stockMoveRepository);
  }

  private void givenAnOutboundOperationType() {
    given(stockLocationRepository.findById(DemandFixtures.LOCATION_ID))
        .willReturn(Optional.of(MovementFixtures.internalLocation()));
    given(pickingTypeRepository.find(StockFixtures.FACILITY_ID, PickingDirection.OUTBOUND))
        .willReturn(Optional.of(MovementFixtures.outboundType()));
    // 寫入後回傳的就是寫進去的那些。真實的持久層會多帶一個版號，而「回傳的是寫入後的樣子」
    // 這條性質由 StockMovementPersistence 的整合測試守著——這裡只需要它不吞掉輸入。
    given(stockMoveRepository.saveAll(org.mockito.ArgumentMatchers.any()))
        .willAnswer(invocation -> List.copyOf(invocation.getArgument(0, Collection.class)));
  }

  @SuppressWarnings("unchecked")
  private List<StockMove> savedMoves() {
    ArgumentCaptor<Collection<StockMove>> captor = ArgumentCaptor.forClass(Collection.class);
    then(stockMoveRepository).should().saveAll(captor.capture());
    return List.copyOf(captor.getValue());
  }

  private Demand demand(String skuCode, int quantity) {
    return DemandFixtures.demand(IdGenerator.nextId(), skuCode, quantity);
  }
}
