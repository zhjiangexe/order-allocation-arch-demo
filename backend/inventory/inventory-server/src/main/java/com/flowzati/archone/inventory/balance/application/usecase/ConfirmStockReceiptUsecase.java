package com.flowzati.archone.inventory.balance.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.balance.application.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.balance.application.invocation.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.port.StockAvailabilityIncreasedPublisher;
import com.flowzati.archone.inventory.balance.application.service.InboundReceiptCompleter;
import com.flowzati.archone.inventory.balance.domain.valueobject.ReceivingBatchIdentity;
import com.flowzati.archone.inventory.movement.application.service.InboundReceiptRegistrar;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 同步確認一段式收貨。
 *
 * <p>inbound operation、move、move line、實體庫存增加與 availability Outbox 事實在同一個
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
    private final StockAvailabilityIncreasedPublisher availabilityPublisher;

    public ConfirmStockReceiptUsecase(
            BusinessClock appClock,
            InboundReceiptRegistrar inboundReceiptRegistrar,
            InboundReceiptCompleter inboundReceiptCompleter,
            StockAvailabilityIncreasedPublisher availabilityPublisher) {
        this.appClock = appClock;
        this.inboundReceiptRegistrar = inboundReceiptRegistrar;
        this.inboundReceiptCompleter = inboundReceiptCompleter;
        this.availabilityPublisher = availabilityPublisher;
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
                incoming, new ReceivingBatchIdentity(command.inDate(), command.expiryDate()), now);

        availabilityPublisher.publish(new StockAvailabilityIncreased(
                command.ownerId(), command.facilityId(), command.locationId(), command.sku(), command.quantity(), now));
    }
}
