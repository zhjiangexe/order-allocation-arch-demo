package com.flowzati.archone.allocation.application.movement;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockPicking;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPickingRepository;
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
    UUID warehouseId = stockLocationRepository.findById(demand.locationId())
        .map(StockLocation::getWarehouseId)
        .orElseThrow(() -> new IllegalStateException(
            "Location " + demand.locationId() + " no longer exists"));
    PickingType type = pickingTypeRepository.find(warehouseId, PickingDirection.OUTBOUND)
        .orElseThrow(() -> new IllegalStateException(
            "Warehouse " + warehouseId + " has no outbound operation type"));

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
}
