package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.state.AssignmentQueueKey;
import com.flowzati.archone.inventory.allocation.application.usecase.AssignNextStockOperationUsecase;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style consumer for physical stock availability facts handled by Allocation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationInventoryAvailabilityEventConsumer {

    private final AssignNextStockOperationUsecase assignNextStockOperationUsecase;

    public AllocationInventoryAvailabilityEventConsumer(
            AssignNextStockOperationUsecase assignNextStockOperationUsecase) {
        this.assignNextStockOperationUsecase = assignNextStockOperationUsecase;
    }

    @Bean
    IntegrationEventDispatcher allocationInventoryAvailabilityIntegrationEventDispatcher(
            IntegrationEventDispatcherFactory factory) {
        // 建立 Allocation context 的庫存可用性事件 dispatcher。
        //
        // 這裡只負責「訂閱哪個 destination、收到哪種 integration event 後呼叫哪個 handler」；
        // 不在 messaging adapter 裡實作配貨規則。真正的等待需求配貨由
        // AssignNextStockOperationUsecase 負責，scheduler 也會共用同一個 transaction operation。
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
                // STOCK_EVENTS 是 Inventory／StockQuant 發布實際庫存增加事實的 destination。
                .forDestination(InventoryEventDestinations.STOCK_EVENTS)
                // 入庫完成後只對同一個 owner、facility、location、SKU 重新掃描等待中的 StockMove。
                // envelope 在這裡被拆開，usecase 只收到 transport-neutral 的 integration event 欄位。
                .onEvent(
                        StockAvailabilityIncreasedIntegrationEvent.class,
                        envelope -> onStockAvailabilityIncreased(envelope.event()))
                .build();

        // 使用固定 subscription name 讓 Inbox／consumer idempotency 能辨識這個訂閱者；
        // factory 會把 handlers 包裝成實際的 IntegrationEventDispatcher。
        return factory.make(AllocationSubscriberIds.INVENTORY_AVAILABILITY, handlers);
    }

    void onStockAvailabilityIncreased(StockAvailabilityIncreasedIntegrationEvent event) {
        // Availability event 是低延遲觸發來源；定期 reconciliation scheduler 也會呼叫同一個
        // AssignNextStockOperationUsecase，兩者因此共用 FIFO、FEFO 與 ship-complete 規則。
        assignNextStockOperationUsecase.execute(
                new AssignmentQueueKey(event.getOwnerId(), event.getLocationId(), event.getSku()));
    }
}
