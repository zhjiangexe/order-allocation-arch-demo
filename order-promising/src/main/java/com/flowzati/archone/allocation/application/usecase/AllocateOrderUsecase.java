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
import com.flowzati.archone.allocation.domain.repository.DemandRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AllocateOrderUsecase {
  private final InboxRepo inboxRepo;
  private final DemandRepository demandRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;
  private final BusinessCalendar businessCalendar;

  public AllocateOrderUsecase(
      InboxRepo inboxRepo,
      DemandRepository demandRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock,
      BusinessCalendar businessCalendar) {
    this.inboxRepo = inboxRepo;
    this.demandRepository = demandRepository;
    this.stockPoolRepository = stockPoolRepository;
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

    // 查的是待配需求，不是訂單。已經配到的行不會出現在 demand_lines 裡，所以「這張單還需不
    // 需要配」由 view 回答——不看訂單狀態，那是落後視圖，拿它當閘門會讓同一筆需求被預留兩次。
    //
    // 查無需求是正常結果而非錯誤：這則命令重送、或訂單已被取消，都會走到這裡。
    Demand demand = demandRepository.findByOrderId(command.orderId()).orElse(null);
    if (demand == null) {
      return;
    }

    Instant now = clock.instant();

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

    if (allocationCoordinator.allocateOrder(demand, batchesBySku, now)
        == AllocationOutcome.ALLOCATED) {
      return;
    }
    allocationCoordinator.backorderOrder(demand, now);
  }

}
