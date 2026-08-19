package com.flowzati.archone.inventory.allocation.entrypoint.messaging;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.inventory.allocation.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.inventory.allocation.application.TransactionalAllocationAttempt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tram-style consumer for physical stock availability facts handled by Allocation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
public class AllocationInventoryAvailabilityEventConsumer {

  private final TransactionalAllocationAttempt allocationAttempt;

  public AllocationInventoryAvailabilityEventConsumer(TransactionalAllocationAttempt allocationAttempt) {
    this.allocationAttempt = allocationAttempt;
  }

  @Bean
  IntegrationEventDispatcher allocationInventoryAvailabilityIntegrationEventDispatcher(
      IntegrationEventDispatcherFactory factory
  ) {
    // 建立 Allocation context 的庫存可用性事件 dispatcher。
    //
    // 這裡只負責「訂閱哪個 destination、收到哪種 integration event 後呼叫哪個 handler」；
    // 不在 messaging adapter 裡實作配貨規則。真正的等待需求配貨由
    // TransactionalAllocationAttempt 負責，scheduler 也會共用同一個 transaction operation。
    IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder
        // STOCK_EVENTS 是 Inventory／StockQuant 發布實際庫存增加事實的 destination。
        .forDestination(InventoryChannels.STOCK_EVENTS)
        // 入庫完成後只對同一個 owner、facility、location、SKU 重新掃描等待中的 StockMove。
        // envelope 在這裡被拆開，usecase 只收到 transport-neutral 的 integration event 欄位。
        .onEvent(StockAvailabilityIncreasedIntegrationEvent.class,
            envelope -> onStockAvailabilityIncreased(envelope.event()))
        .build();

    // 使用固定 subscription name 讓 Inbox／consumer idempotency 能辨識這個訂閱者；
    // factory 會把 handlers 包裝成實際的 IntegrationEventDispatcher。
    return factory.make(AllocationEventSubscriptions.INVENTORY_AVAILABILITY, handlers);
  }

  void onStockAvailabilityIncreased(StockAvailabilityIncreasedIntegrationEvent event) {
    // Availability event 是低延遲觸發來源；定期 reconciliation scheduler 也會呼叫同一個
    // TransactionalAllocationAttempt，兩者因此共用 FIFO、FEFO 與 ship-complete 規則。
    allocationAttempt.attempt(new AllocateWaitingDemandCommand(
        event.getOwnerId(), event.getFacilityId(), event.getLocationId(), event.getSku()));
  }
}
