package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.store.OrderStockMovementStore;
import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.result.StockOperationRegistrationResult;
import com.flowzati.archone.inventory.movement.application.service.StockOperationRegistrar;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 訂單首次配貨的 application 入口。
 *
 * <p>這裡只負責編排，不實作供需演算法。流程依序為：
 *
 * <ol>
 *   <li>order adapter normalizes a source-neutral movement command;
 *   <li>the registrar idempotently persists one confirmed operation and its confirmed moves;
 *   <li>the shared assignment pipeline attempts only that operation;
 *   <li>a predecessor or shortage leaves the canonical movements confirmed for a later wake.</li>
 * </ol>
 */
@Service
public class AllocateOrderUsecase {

    private final OrderStockMovementStore orderSource;
    private final StockOperationRegistrar movementRegistrar;
    private final StockOperationAssignmentCoordinator assignmentCoordinator;

    public AllocateOrderUsecase(
            OrderStockMovementStore orderSource,
            StockOperationRegistrar movementRegistrar,
            StockOperationAssignmentCoordinator assignmentCoordinator) {
        this.orderSource = orderSource;
        this.movementRegistrar = movementRegistrar;
        this.assignmentCoordinator = assignmentCoordinator;
    }

    /**
     * Inbox claim, movement registration and a possible assignment share this transaction.
     */
    @Transactional
    public void execute(AllocateOrderCommand command) {
        // 1. 將 Order-owned projection 轉成 Inventory movement command，不傳入 Order aggregate。
        Optional<RegisterStockOperationCommand> source = orderSource.find(command.orderId());
        if (source.isEmpty()) {
            // 來源不存在或已取消時沒有 movement 可建立，正常結束。
            return;
        }

        // 2. 冪等建立 CONFIRMED Operation 與 Moves；此時尚未預留庫存。
        StockOperationRegistrationResult registered = movementRegistrar.register(source.get());

        // 3. 只嘗試這組 movement；前單阻擋或缺貨時保留 CONFIRMED，等待日後喚醒。
        assignmentCoordinator.tryAssign(registered.operation().id());
    }
}
