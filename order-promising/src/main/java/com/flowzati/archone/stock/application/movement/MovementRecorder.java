package com.flowzati.archone.stock.application.movement;

import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.catalog.domain.model.PickingType;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 搬運的第一個動作：**建立**（Odoo 的 {@code stock.move._action_confirm}）。
 *
 * <p><b>它不配貨。</b>這一點是這個型別存在的全部理由——入庫要做的是同一件事（解析作業類型、
 * 建單據、建搬運），但它沒有 {@code Demand}、沒有 ATP 判斷、沒有整籃檢查。建立若內聯在配貨的
 * usecase 裡，入庫只剩兩條路：複製一份，或把入庫硬塞進一支名為「配貨」的 usecase。
 *
 * <p>下一個 change 的入庫會在這裡加 {@code recordInbound}——**兩個方法而不是一個帶方向參數
 * 的**，因為兩邊的輸入本來就不同型。
 */
@Component
public class MovementRecorder {

  private final StockLocationRepository stockLocationRepository;
  private final PickingTypeRepository pickingTypeRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockMoveRepository stockMoveRepository;

  public MovementRecorder(
      StockLocationRepository stockLocationRepository,
      PickingTypeRepository pickingTypeRepository,
      StockPickingRepository stockPickingRepository,
      StockMoveRepository stockMoveRepository) {
    this.stockLocationRepository = stockLocationRepository;
    this.pickingTypeRepository = pickingTypeRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.stockMoveRepository = stockMoveRepository;
  }

  /**
   * 為這張需求建一張出庫作業單與每一條行的搬運，狀態是「還在等貨」。
   *
   * <p>起訖取自作業類型的預設值：庫存位置 → 客戶。作業類型以倉為鍵（Odoo 也是），而這裡手上
   * 只有位置，所以先反查它的倉。
   *
   * <p>倉沒有設出庫類型時**拋錯而不是靜默略過**：那張單無處可去，而「收下卻不記」會讓需求
   * 消失得無聲無息——它不會出現在任何佇列裡，因為佇列讀的是搬運。
   *
   * <p><b>回傳建好的搬運</b>，而不是 void 或單據 id：呼叫端接著要把它們交給鎖定那一步，回傳
   * 讓那一步不必用 {@code order_line_id} 把同一批列再讀一次。
   */
  public List<StockMove> recordOutbound(Demand demand, Instant now) {
    PickingType type = operationTypeFor(demand.locationId(), PickingDirection.OUTBOUND);

    UUID pickingId = IdGenerator.nextId();
    stockPickingRepository.save(new StockPicking(
        pickingId,
        type.id(),
        demand.ownerId(),
        demand.orderId(),
        type.defaultFromLocationId(),
        type.defaultToLocationId()));

    List<StockMove> created = demand.lines().stream()
        .map(demandLine -> StockMove.confirmed(
            IdGenerator.nextId(),
            pickingId,
            demand.ownerId(),
            demandLine.skuCode(),
            type.defaultFromLocationId(),
            type.defaultToLocationId(),
            demandLine.orderLineId(),
            demandLine.quantity(),
            now))
        .toList();
    // 回傳寫入後的樣子，不是剛建構的那些：同一個交易裡接著鎖定會對同一列寫第二次，而那一次
    // 必須帶著第一次之後的版號，否則持久層會當成一列全新的資料。
    return stockMoveRepository.saveAll(created);
  }

  /**
   * 為到貨建一張入庫作業單與一段搬運：供應商 → 該倉的庫存位置。
   *
   * <p><b>單據不帶訂單，搬運不帶訂單行。</b>沒有任何東西是透過這個系統訂的——貨是貨主的，
   * 依貨主自己的安排到達。這正是那兩個欄位可空的理由，而在此之前它們從來沒有真的空過。
   *
   * <p><b>它不查可承諾量、不預留、不做整籃判斷。</b>那些屬於滿足需求，而這裡沒有需求被滿足。
   * 這個方法與 {@link #recordOutbound} 分開的理由就在這一句。
   *
   * <p>與出庫同一個判準：倉沒有設入庫作業類型時拋錯，而不是靜默少建一張單。
   */
  public List<StockMove> recordInbound(
      UUID ownerId, UUID locationId, String skuCode, int quantity, Instant now) {
    PickingType type = operationTypeFor(locationId, PickingDirection.INBOUND);

    UUID pickingId = IdGenerator.nextId();
    stockPickingRepository.save(new StockPicking(
        pickingId,
        type.id(),
        ownerId,
        null,
        type.defaultFromLocationId(),
        type.defaultToLocationId()));

    return stockMoveRepository.saveAll(List.of(StockMove.confirmed(
        IdGenerator.nextId(),
        pickingId,
        ownerId,
        skuCode,
        type.defaultFromLocationId(),
        type.defaultToLocationId(),
        null,
        quantity,
        now)));
  }

  /**
   * 位置 → 倉 → 該方向的作業類型。
   *
   * <p>作業類型以倉為鍵（Odoo 也是），而兩個入口手上都只有位置。
   */
  private PickingType operationTypeFor(UUID locationId, PickingDirection direction) {
    UUID facilityId = stockLocationRepository.findById(locationId)
        .map(StockLocation::getFacilityId)
        .orElseThrow(() -> new IllegalStateException(
            "Location " + locationId + " no longer exists"));
    return pickingTypeRepository.find(facilityId, direction)
        .orElseThrow(() -> new IllegalStateException(
            "Warehouse " + facilityId + " has no " + direction.name().toLowerCase()
                + " operation type"));
  }
}
