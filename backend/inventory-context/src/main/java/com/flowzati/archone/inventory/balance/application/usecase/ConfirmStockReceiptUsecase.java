package com.flowzati.archone.inventory.balance.application.usecase;

import com.flowzati.archone.contracts.inventory.v1.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.balance.application.InboundReceiptCompleter;
import com.flowzati.archone.inventory.balance.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.movement.application.InboundReceiptRegistrar;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.messaging.events.PublicationTarget;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 同步確認一段式收貨。
 *
 * <p>inbound picking、move、move line、實體庫存增加與 availability Outbox 事實在同一個
 * 交易內完成。庫存只由完成搬運的明細增加，不另開直接寫數量的路徑。
 * 呼叫者指定實際收貨庫位；本 use case 在交易內驗證它是該 Facility 的 internal location。
 *
 * <p>這裡不執行 outbound 配貨。availability Integration Event 與 reconciliation scheduler
 * 都在收貨提交後另開 allocation transaction，避免 inbound 交易承擔等待佇列的負載。
 */
@Service
public class ConfirmStockReceiptUsecase {

    private final BusinessClock appClock;
    private final InboundReceiptRegistrar inboundReceiptRegistrar;
    private final InboundReceiptCompleter inboundReceiptCompleter;
    private final IntegrationEventPublisher integrationEventPublisher;

    public ConfirmStockReceiptUsecase(
            BusinessClock appClock,
            InboundReceiptRegistrar inboundReceiptRegistrar,
            InboundReceiptCompleter inboundReceiptCompleter,
            IntegrationEventPublisher integrationEventPublisher) {
        this.appClock = appClock;
        this.inboundReceiptRegistrar = inboundReceiptRegistrar;
        this.inboundReceiptCompleter = inboundReceiptCompleter;
        this.integrationEventPublisher = integrationEventPublisher;
    }

    /** Transport-neutral application entrypoint; request idempotency belongs to the caller boundary. */
    @Transactional
    public void execute(ConfirmStockReceiptCommand command) {
        receive(command);
    }

    private void receive(ConfirmStockReceiptCommand command) {
        Instant now = appClock.instant();

        List<StockMove> incoming = inboundReceiptRegistrar.register(
                command.facilityId(), command.ownerId(), command.locationId(), command.sku(), command.quantity(), now);

        inboundReceiptCompleter.complete(
                incoming, new InboundReceiptCompleter.BatchIdentity(command.inDate(), command.expiryDate()), now);

        String contentionKey = StockContentionKey.of(command.ownerId(), command.facilityId());
        integrationEventPublisher.publish(
                new StockAvailabilityIncreasedIntegrationEvent(
                        IdGenerator.nextId(),
                        command.ownerId(),
                        command.facilityId(),
                        command.locationId(),
                        command.sku(),
                        command.quantity()),
                new AggregateReference(InventoryAggregateTypes.STOCK_POOL, contentionKey),
                new PublicationTarget(InventoryChannels.STOCK_EVENTS, contentionKey),
                now);
    }
}
