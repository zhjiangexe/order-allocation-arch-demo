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

    // 一次配貨只取一個 (貨主, 倉, SKU) 的批,因此這裡踩在「這張單只碰一個 SKU」的假設上。
    // 收單政策目前保證它成立;放寬多 SKU 時,這裡要改成取多組批並做整籃判斷(見 roadmap R8)。
    String skuCode = requireSingleSku(demand);
    Instant now = clock.instant();

    // 篩選與排序都在資料庫做。批數只會隨時間成長,把配不到的載進記憶體只為了丟掉是錯的
    // 方向;而且 (expiry_date, in_date, id) 正是索引的順序,資料庫本來就排好了。
    //
    // 一批可配的都沒有不是例外——那是「缺貨」這個正常結果,由配貨流程判定後掛帳。原本的
    // 「查無庫存池就丟例外」在分批之後會把每一次缺貨都變成訊息處理失敗。
    List<StockPool> allocatableBatches = stockPoolRepository.findAllocatableBatchesInFefoOrder(
        demand.ownerId(),
        demand.nodeId(),
        skuCode,
        businessCalendar.today());

    if (allocationCoordinator.allocateOrder(demand, allocatableBatches, now)
        == AllocationOutcome.ALLOCATED) {
      return;
    }
    allocationCoordinator.backorderOrder(demand, now);
  }

  /**
   * 這筆需求唯一涉及的 SKU。
   *
   * <p>語意是「此呼叫端踩在『一張單只碰一個 SKU』這個假設上」。R8 放寬多 SKU 時，搜尋這個
   * 方法就是完整的待修清單——把假設集中在一個有名字的地方，而不是散成各處的
   * {@code totalsBySku().keySet()} 取首個。
   */
  private static String requireSingleSku(Demand demand) {
    var skuCodes = demand.totalsBySku().keySet();
    if (skuCodes.size() != 1) {
      throw new IllegalStateException(
          "This caller assumes a single-SKU order, but the demand spans " + skuCodes.size()
              + " SKUs");
    }
    return skuCodes.iterator().next();
  }
}
