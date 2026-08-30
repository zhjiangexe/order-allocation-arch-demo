package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderPlaced;
import com.flowzati.archone.ordering.application.port.OrderPlacedPublisher;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.entity.OrderLine;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlaceOrderUsecase {

    private final OrderRepository orderRepository;
    private final BusinessClock clock;
    private final OrderPlacedPublisher orderPlacedPublisher;

    public PlaceOrderUsecase(
            OrderRepository orderRepository, BusinessClock clock, OrderPlacedPublisher orderPlacedPublisher) {
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.orderPlacedPublisher = orderPlacedPublisher;
    }

    /**
     * 回傳整個 {@link Order} 而不只是 id，讓 HTTP 邊界能用與單筆查詢相同的表示型別回應下單
     * 結果——客戶端因此只需要一個訂單模型，而不是「建立時拿到一種、查詢時拿到另一種」。
     *
     * <p>不預先查詢 SKU 是否存在於主檔：行的 {@code (owner_id, sku_code)} 有外鍵，儲存層會
     * 擋下不存在的組合。多一層應用層檢查只能換到更好的錯誤訊息，不改變正確性，卻多了一條
     * 「檢查通過但寫入時已被刪除」的競爭路徑。
     */
    @Transactional
    public Order placeOrder(PlaceOrderCommand command) {
        UUID orderId = IdGenerator.nextId();
        // 收單時刻一律取自我們的時鐘，不接受呼叫端提供：訂單先後的排序靠它，讓外部決定就等於
        // 讓外部決定誰先被配到貨。上游的下單時刻則原樣收下，不做修正也不在缺漏時補值。
        Instant receivedAt = clock.instant();
        Order placedOrder = Order.place(
                orderId,
                command.ownerId(),
                command.externalOrderNo(),
                command.toDeliveryTerms(),
                toLines(command),
                receivedAt,
                command.placedAt());
        orderRepository.save(placedOrder);
        publishOrderPlaced(placedOrder, receivedAt);
        return placedOrder;
    }

    private void publishOrderPlaced(Order order, Instant receivedAt) {
        orderPlacedPublisher.publish(new OrderPlaced(
                order.getId(), order.getOwnerId(), order.getDeliveryTerms().facilityId(), receivedAt));
    }

    /** 行號依提交順序產生，從 1 起算。 */
    private static List<OrderLine> toLines(PlaceOrderCommand command) {
        if (command.lines() == null) {
            throw new IllegalArgumentException("Order must contain at least one line");
        }
        List<OrderLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (PlaceOrderCommand.Line line : command.lines()) {
            lines.add(OrderLine.create(
                    IdGenerator.nextId(), lineNo++, command.ownerId(), line.skuCode(), line.quantity()));
        }
        return List.copyOf(lines);
    }
}
