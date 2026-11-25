package com.flowzati.archone.inventory.balance.application.usecase;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.balance.application.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.balance.application.invocation.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.port.StockAvailabilityIncreasedPublisher;
import com.flowzati.archone.inventory.balance.application.service.InboundReceiptCompletionService;
import com.flowzati.archone.inventory.balance.application.store.StockReceiptRequestStore;
import com.flowzati.archone.inventory.balance.domain.valueobject.ReceivingBatchIdentity;
import com.flowzati.archone.inventory.movement.application.service.InboundReceiptRegistrationService;
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

    private final StockReceiptRequestStore stockReceiptRequestStore;
    private final BusinessClock appClock;
    private final InboundReceiptRegistrationService inboundReceiptRegistrationService;
    private final InboundReceiptCompletionService inboundReceiptCompletionService;
    private final StockAvailabilityIncreasedPublisher availabilityPublisher;

    public ConfirmStockReceiptUsecase(
            StockReceiptRequestStore stockReceiptRequestStore,
            BusinessClock appClock,
            InboundReceiptRegistrationService inboundReceiptRegistrationService,
            InboundReceiptCompletionService inboundReceiptCompletionService,
            StockAvailabilityIncreasedPublisher availabilityPublisher) {
        this.stockReceiptRequestStore = stockReceiptRequestStore;
        this.appClock = appClock;
        this.inboundReceiptRegistrationService = inboundReceiptRegistrationService;
        this.inboundReceiptCompletionService = inboundReceiptCompletionService;
        this.availabilityPublisher = availabilityPublisher;
    }

    /**
     * 同一交易先登記 {@code receiptId} 再入庫：相同 ID＋相同內容重送時直接結束，不重複入庫；相同 ID＋不同內容
     * 由 store 拋出衝突。
     */
    @Transactional
    public void execute(ConfirmStockReceiptCommand command) {
        if (!stockReceiptRequestStore.claimIfNew(command)) {
            return;
        }
        receive(command);
    }

    private void receive(ConfirmStockReceiptCommand command) {
        Instant now = appClock.instant();

        List<StockMove> incoming = inboundReceiptRegistrationService.register(
                command.facilityId(), command.ownerId(), command.locationId(), command.sku(), command.quantity(), now);

        inboundReceiptCompletionService.complete(
                incoming, new ReceivingBatchIdentity(command.inDate(), command.expiryDate()), now);

        availabilityPublisher.publish(new StockAvailabilityIncreased(
                command.ownerId(), command.facilityId(), command.locationId(), command.sku(), command.quantity(), now));
    }
}
