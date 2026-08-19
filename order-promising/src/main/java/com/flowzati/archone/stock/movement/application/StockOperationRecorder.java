package com.flowzati.archone.stock.movement.application;

import com.flowzati.archone.stock.movement.domain.aggregate.StockMove;
import com.flowzati.archone.stock.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.stock.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.catalog.domain.aggregate.PickingType;
import com.flowzati.archone.catalog.domain.aggregate.StockLocation;
import com.flowzati.archone.catalog.domain.repository.PickingTypeRepository;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.foundation.identity.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 搬運的第一個動作：**建立**（Odoo 的 {@code stock.move._action_confirm}）。
 *
 * <p><b>它不配貨。</b>這個元件保留 supply-only inbound recording；stock-consuming outbound
 * execution 一律由 allocation demand acceptance transaction 建立。
 *
 * <p><b>picking 是這兩條作業流程的必要分組，不是 {@link StockMove} 型別的全域
 * 不變式。</b>{@code recordOutbound} 每張訂單建一張獨立 picking，該單全部 move 共用。
 * 但通用 move 仍可沒有 picking，
 * 例如未來的盤點調整。因此不需要一個強制所有 move 都造 picking 的 Factory。
 */
@Component
public class StockOperationRecorder {

  private final StockLocationRepository stockLocationRepository;
  private final PickingTypeRepository pickingTypeRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockMoveRepository stockMoveRepository;

  public StockOperationRecorder(
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
   * 為一段式收貨建立一張入庫 picking 與一段搬運：供應商位置 → 呼叫者選定的內部位置。
   *
   * <p>picking 不帶 order、move 不帶 order line，因為這是 stock context 的收貨作業，不是
   * outbound order demand。作業類型提供預設來源；目的地使用 {@code locationId}，不可被
   * {@link PickingType#defaultToLocationId()} 覆蓋。這一步只記錄待完成的 warehouse execution，
   * 不直接改庫存。
   */
  public List<StockMove> recordInbound(
      UUID facilityId,
      UUID ownerId,
      UUID locationId,
      String skuCode,
      int quantity,
      Instant now
  ) {
    PickingType type = operationTypeFor(facilityId, locationId, PickingDirection.INBOUND);

    UUID pickingId = IdGenerator.nextId();
    stockPickingRepository.save(StockPicking.confirmedInbound(
        pickingId,
        type.id(),
        ownerId,
        type.defaultFromLocationId(),
        locationId));

    return stockMoveRepository.saveAll(List.of(StockMove.confirmed(
        IdGenerator.nextId(),
        pickingId,
        ownerId,
        skuCode,
        type.defaultFromLocationId(),
        locationId,
        null,
        quantity,
        now)));
  }

  /**
   * 位置 → Facility → 該方向的作業類型。
   *
   * <p>入口同時傳入 Facility 與實際操作位置：先確認位置存在且屬於該
   * Facility，再以 Facility 與方向解析作業類型。這避免需求的位置與作業類型來自
   * 不同 Facility，卻仍建出一張起點錯誤的搬運。
   */
  private PickingType operationTypeFor(
      UUID facilityId, UUID locationId, PickingDirection direction) {
    StockLocation location = stockLocationRepository.findById(locationId)
        .orElseThrow(() -> new IllegalStateException("Stock location " + locationId + " no longer exists"));
    if (!facilityId.equals(location.getFacilityId())) {
      throw new IllegalArgumentException("Stock location " + locationId + " does not belong to facility " + facilityId);
    }

    return pickingTypeRepository.find(facilityId, direction)
        .orElseThrow(() -> new IllegalStateException(
            "Facility " + facilityId + " has no " + direction.name().toLowerCase() + " operation type"));
  }
}
