package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.result.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 單次補配的交易入口，供庫存增加事件與定時掃描共用。 */
@Service
public class AssignNextStockOperationUsecase {
    private final StockOperationAssignmentCoordinator coordinator;

    public AssignNextStockOperationUsecase(StockOperationAssignmentCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 每次只嘗試一張需求；已有 Inbox 交易時加入，否則建立獨立交易。 */
    @Transactional
    public Optional<StockOperationAssignmentResult> execute(AssignmentQueueKey queueKey) {
        return coordinator.tryAssignNext(queueKey);
    }
}
