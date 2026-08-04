package com.flowzati.archone.stock.entrypoint.kafka;

import com.flowzati.archone.stock.application.command.ReplenishStockCommand;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.stock.application.retry.AllocationRetryContext;
import com.flowzati.archone.stock.application.retry.AllocationRetryExecutor;
import com.flowzati.archone.stock.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import com.flowzati.archone.common.messaging.kafka.KafkaIntegrationEventHandler;
import org.springframework.stereotype.Component;

@Component
class StockReplenishedIntegrationEventHandler
    implements KafkaIntegrationEventHandler<StockReplenishedIntegrationEvent> {

  private final ReplenishmentUsecase replenishmentUsecase;
  private final StockLocationRepository stockLocationRepository;
  private final AllocationRetryExecutor retryExecutor;

  StockReplenishedIntegrationEventHandler(
      ReplenishmentUsecase replenishmentUsecase,
      StockLocationRepository stockLocationRepository,
      AllocationRetryExecutor retryExecutor
  ) {
    this.replenishmentUsecase = replenishmentUsecase;
    this.stockLocationRepository = stockLocationRepository;
    this.retryExecutor = retryExecutor;
  }

  @Override
  public String topic() {
    return InventoryEventTopics.STOCK_EVENTS;
  }

  @Override
  public Class<StockReplenishedIntegrationEvent> eventClass() {
    return StockReplenishedIntegrationEvent.class;
  }

  @Override
  public void handleTyped(StockReplenishedIntegrationEvent event, MessageMetadata metadata) {
    ReplenishStockCommand command = new ReplenishStockCommand(
        event.getOwnerId(),
        event.getFacilityId(),
        internalLocationOf(event.getFacilityId()),
        event.getSku(),
        event.getInDate(),
        event.getExpiryDate(),
        event.getQuantity());
    InboundCommand<ReplenishStockCommand> inbound = new InboundCommand<>(command, metadata);
    retryExecutor.execute(
        new AllocationRetryContext("replenish-stock", metadata.eventId(), null, event.getSku()),
        () -> replenishmentUsecase.handle(inbound));
  }

  /**
   * 倉 → 該倉的內部位置。**解析在這一層，不在 usecase。**
   *
   * <p>對外的契約說倉——貨主的上游系統不知道也不該知道倉裡怎麼編排位置。entrypoint 的職責
   * 就是把外部詞彙翻成內部詞彙，翻完之後 allocation 只說位置。
   *
   * <p>倉沒有內部位置時**拋錯而不是靜默略過**：那批貨無處可放，而「收下卻不記」會讓實體與帳
   * 從此對不上，且沒有任何訊號。
   */
  private java.util.UUID internalLocationOf(java.util.UUID facilityId) {
    return stockLocationRepository.findInternalOf(facilityId)
        .map(com.flowzati.archone.catalog.domain.model.StockLocation::getId)
        .orElseThrow(() -> new IllegalStateException(
            "Warehouse " + facilityId + " has no internal stock location"));
  }
}
