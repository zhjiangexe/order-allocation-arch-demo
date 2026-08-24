package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.service.demand.AllocationDemandRegistrar;
import com.flowzati.archone.inventory.allocation.application.service.reservation.PendingDemandAllocator;
import com.flowzati.archone.inventory.allocation.application.source.order.OrderAllocationDemandSource;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 訂單首次配貨的 application 入口。
 *
 * <p>這裡只負責編排，不實作供需演算法。流程依序為：
 *
 * <ol>
 *   <li>用 order adapter 把訂單 read model 轉成共用 demand command；
 *   <li>冪等接受 {@code ORDER/orderId/PRIMARY} demand，建立 outbound execution；
 *   <li>嘗試配置剛接受的 demand，不會改去配置另一筆 queue head；
 *   <li>allocator 會檢查 demand 是否為全部 required-SKU queues 的 FIFO head；
 *   <li>若庫存不足或 FIFO 尚未輪到，保留 PENDING 等後續 availability/reconciliation。</li>
 * </ol>
 */
@Service
public class AllocateOrderUsecase {

    private final OrderAllocationDemandSource orderSource;
    private final AllocationDemandRegistrar demandRegistrar;
    private final PendingDemandAllocator pendingDemandAllocator;
    private final BusinessClock appClock;

    public AllocateOrderUsecase(
            OrderAllocationDemandSource orderSource,
            AllocationDemandRegistrar demandRegistrar,
            PendingDemandAllocator pendingDemandAllocator,
            BusinessClock appClock) {
        this.orderSource = orderSource;
        this.demandRegistrar = demandRegistrar;
        this.pendingDemandAllocator = pendingDemandAllocator;
        this.appClock = appClock;
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
        AllocationDemand accepted = demandRegistrar.register(source.get()).demand();

        // 首次路徑只嘗試剛接受的 demand；FIFO blocked 時留在 backlog，不改去配置其他訂單。
        pendingDemandAllocator.tryAllocateDemand(accepted, appClock.today(), appClock.instant());
    }
}
