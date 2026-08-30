package com.flowzati.archone.ordering.infrastructure.messaging;

import com.flowzati.archone.contracts.stock.v1.StockContentionKey;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves the message partition key used by Ordering Integration Events. */
@Component
public class OrderingPartitionKeyResolver {

    private static final String STOCK_STRATEGY = "stock";
    private static final Logger log = LoggerFactory.getLogger(OrderingPartitionKeyResolver.class);

    private final String partitionKeyStrategy;

    public OrderingPartitionKeyResolver(
            @Value("${archone.allocation.partition-key-strategy:order-id}") String partitionKeyStrategy) {
        this.partitionKeyStrategy = partitionKeyStrategy;
        log.info("archone.allocation.partition-key-strategy={}", partitionKeyStrategy);
    }

    /**
     * {@code stock} 策略下以 {@link StockContentionKey}（{@code (貨主, 倉)}）當 partition key，
     * 讓所有會碰到同一批庫存的事件收斂進同一個 partition；預設沿用 orderId。
     *
     * <p>這只決定 Kafka message key，不影響 outbox row 的 {@code aggregateid}。key 的粒度必須
     * 覆蓋一次 allocation 交易可能碰到的所有庫存，因此 stock 策略不包含 SKU。
     */
    public String resolve(UUID orderId, UUID ownerId, UUID facilityId) {
        return STOCK_STRATEGY.equals(partitionKeyStrategy)
                ? StockContentionKey.of(ownerId, facilityId)
                : orderId.toString();
    }
}
