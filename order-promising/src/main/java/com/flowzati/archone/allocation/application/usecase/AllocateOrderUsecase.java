package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.service.AllocationOutcome;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
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
import com.flowzati.archone.allocation.domain.repository.DemandRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;

@Service
public class AllocateOrderUsecase {
  private final InboxRepo inboxRepo;
  private final DemandRepository demandRepository;
  private final StockPoolRepository stockPoolRepository;
  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final PickingTypeRepository pickingTypeRepository;
  private final StockLocationRepository stockLocationRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;
  private final BusinessCalendar businessCalendar;

  public AllocateOrderUsecase(
      InboxRepo inboxRepo,
      DemandRepository demandRepository,
      StockPoolRepository stockPoolRepository,
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      PickingTypeRepository pickingTypeRepository,
      StockLocationRepository stockLocationRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock,
      BusinessCalendar businessCalendar) {
    this.inboxRepo = inboxRepo;
    this.demandRepository = demandRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.pickingTypeRepository = pickingTypeRepository;
    this.stockLocationRepository = stockLocationRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
    this.businessCalendar = businessCalendar;
  }

  @Transactional
  public void handle(InboundCommand<AllocateOrderCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    AllocateOrderCommand command = inbound.command();

    // 查的是**還沒被執行層接手的行**。已經建了搬運的行不會出現在 demand_lines 裡，所以
    // 「這張單還需不需要接手」由 view 回答——不看訂單狀態，那是落後視圖，拿它當閘門會讓
    // 同一筆需求被建兩次搬運。
    //
    // 查無需求是正常結果而非錯誤：這則命令重送、或訂單已被取消，都會走到這裡。
    Optional<Demand> demandOpt = demandRepository.findByOrderId(command.orderId());
    if (demandOpt.isEmpty()) {
      return;
    }
    Demand demand = demandOpt.get();
    Instant now = clock.instant();

    // **先建搬運，再配貨。** 即使一件貨都沒有也要建——那讓「還在等貨」成為一列真實資料而
    // 不是一個查詢的副產物，而一張永遠配不到的單因此留得下痕跡（含它等了多久）。
    createMovements(demand, now);

    // 篩選與排序都在資料庫做。批數只會隨時間成長,把配不到的載進記憶體只為了丟掉是錯的
    // 方向;而且 (expiry_date, in_date, id) 正是索引的順序,資料庫本來就排好了。
    //
    // 一批可配的都沒有不是例外——那是「缺貨」這個正常結果,由配貨流程判定後掛帳。原本的
    // 「查無庫存池就丟例外」在分批之後會把每一次缺貨都變成訊息處理失敗。
    // 一次取這張單需要的**每一個** SKU 的批。整籃原子判斷要看全部——只取其中一個 SKU 的批
    // 會讓其餘的行看起來都缺貨。
    Map<String, List<StockPool>> batchesBySku = stockPoolRepository.findAllocatableBatchesBySku(
        demand.ownerId(),
        demand.locationId(),
        demand.totalsBySku().keySet(),
        businessCalendar.today());

    if (allocationCoordinator.allocateOrder(demand, batchesBySku, now) == AllocationOutcome.ALLOCATED) {
      return;
    }
    allocationCoordinator.backorderOrder(demand, now);
  }

  /**
   * 為這張單建一張作業單與每一條行的搬運，狀態是「還在等貨」。
   *
   * <p>起訖取自作業類型的預設值：庫存位置 → 客戶。作業類型以倉為鍵（Odoo 也是），而這裡手上
   * 只有位置，所以先反查它的倉。
   *
   * <p>倉沒有設出庫類型時**拋錯而不是靜默略過**：那張單無處可去，而「收下卻不記」會讓需求
   * 消失得無聲無息——它不會出現在任何佇列裡，因為 view 只回答「還沒被接手的」。
   */
  private void createMovements(Demand demand, Instant now) {
    UUID warehouseId = stockLocationRepository.findById(demand.locationId())
        .map(StockLocation::getWarehouseId)
        .orElseThrow(() -> new IllegalStateException(
            "Location " + demand.locationId() + " no longer exists"));
    PickingType type = pickingTypeRepository.find(warehouseId, PickingDirection.OUTBOUND)
        .orElseThrow(() -> new IllegalStateException(
            "Warehouse " + warehouseId + " has no outbound operation type"));

    UUID pickingId = IdGenerator.nextId();
    stockPickingRepository.save(
        new StockPicking(
            pickingId,
            type.id(),
            demand.ownerId(),
            type.defaultFromLocationId(),
            type.defaultToLocationId()),
        demand.orderId());

    stockMoveRepository.saveAll(demand.lines().stream()
        .map(line -> StockMove.needing(
            IdGenerator.nextId(),
            pickingId,
            demand.ownerId(),
            line.skuCode(),
            type.defaultFromLocationId(),
            type.defaultToLocationId(),
            line.orderLineId(),
            line.quantity(),
            now))
        .toList());
  }
}
