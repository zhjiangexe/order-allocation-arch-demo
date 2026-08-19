package com.flowzati.archone.stock.allocation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.stock.allocation.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.stock.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.stock.allocation.application.demand.AllocationDemandAcceptor;
import com.flowzati.archone.stock.allocation.application.AllocationAttemptCoordinator;
import com.flowzati.archone.stock.allocation.application.source.order.OrderAllocationDemandSource;
import com.flowzati.archone.stock.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.stock.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.stock.allocation.domain.valueobject.WaitingAllocationScope;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 訂單首次配貨的 application 入口。
 *
 * <p>這裡只負責編排，不實作供需演算法。流程依序為：
 *
 * <ol>
 *   <li>用 order adapter 把訂單 read model 轉成共用 demand command；
 *   <li>冪等接受 {@code ORDER/orderId/PRIMARY} demand，建立 outbound execution；
 *   <li>以第一條 canonical line 的 SKU 喚醒共用 allocator；
 *   <li>allocator 仍會檢查 demand 的全部 SKU，不是只配 triggering SKU；
 *   <li>若庫存不足或 FIFO 尚未輪到，保留 PENDING 等後續 availability/reconciliation。</li>
 * </ol>
 */
@Service
public class AllocateOrderUsecase {

  private final OrderAllocationDemandSource orderSource;
  private final AllocationDemandAcceptor demandAcceptor;
  private final AllocationAttemptCoordinator allocationAttempt;
  private final BusinessClock appClock;
  private final int candidateLimit;

  public AllocateOrderUsecase(
      OrderAllocationDemandSource orderSource,
      AllocationDemandAcceptor demandAcceptor,
      AllocationAttemptCoordinator allocationAttempt,
      BusinessClock appClock,
      @Value("${archone.allocation.waiting-demand-batch-limit:200}") int candidateLimit) {
    if (candidateLimit <= 0) {
      throw new IllegalArgumentException("Initial allocation candidate limit must be positive");
    }
    this.orderSource = orderSource;
    this.demandAcceptor = demandAcceptor;
    this.allocationAttempt = allocationAttempt;
    this.appClock = appClock;
    this.candidateLimit = candidateLimit;
  }

  /**
   * Inbox claim、demand acceptance、execution rows，以及可能成功的首次 allocation 共用此 transaction。
   */
  @Transactional
  public void execute(AllocateOrderCommand command) {
    // 來源轉接只讀 order 發布給 allocation 的 read model，不把 Order aggregate 傳入 allocation core。
    Optional<AcceptAllocationDemandCommand> source = orderSource.find(command.orderId());
    if (source.isEmpty()) {
      // 已取消或不存在的來源不會出現在 adapter view；這種情況不建立 demand，也不是錯誤。
      return;
    }

    // Acceptance 只建立/重播 immutable demand 與 outbound execution，還沒有 reserve 庫存。
    AllocationDemand accepted = demandAcceptor.accept(source.get()).demand();

    // triggering SKU 只用來縮小 wake-up 查詢；真正的 FIFO 與庫存檢查仍涵蓋 demand 的所有 SKU。
    String triggeringSku = accepted.lines().stream()
        .min(java.util.Comparator.comparingInt(AllocationDemandLine::lineSequence))
        .orElseThrow()
        .skuCode();
    allocationAttempt.allocateOne(
        new WaitingAllocationScope(accepted.ownerId(), accepted.facilityId(), accepted.locationId(), triggeringSku),
        triggeringSku,
        candidateLimit,
        appClock.today(),
        appClock.instant());
  }
}
