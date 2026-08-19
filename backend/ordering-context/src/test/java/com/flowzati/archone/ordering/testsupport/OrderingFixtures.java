package com.flowzati.archone.ordering.testsupport;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.entity.OrderLine;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 只建立 Ordering domain objects；不 seed Catalog、Inventory 或資料庫。 */
public final class OrderingFixtures {

    public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    public static final Instant DISPATCH_BY = Instant.parse("2026-08-01T08:00:00Z");
    public static final int RELEASE_PRIORITY = 50;

    private OrderingFixtures() {}

    public static DeliveryTerms deliveryTerms() {
        return new DeliveryTerms(
                FACILITY_ID, "100", "台北市中正區重慶南路一段 122 號", LocalDate.of(2026, 8, 1), DISPATCH_BY, RELEASE_PRIORITY);
    }

    public static Order pendingOrder(UUID orderId, String skuCode, int quantity, Instant receivedAt) {
        return order(orderId, OWNER_ID, skuCode, quantity, OrderStatus.PENDING, receivedAt, null, null);
    }

    public static Order backorderedOrder(
            UUID orderId,
            UUID ownerId,
            String skuCode,
            int quantity,
            Instant receivedAt,
            Instant ignoredBackorderedAt,
            Long version) {
        // Waiting demand 的排序時間已改由 movement.createdAt 表達；保留參數讓 mapper 測試清楚標示
        // 歷史資料情境，但 Order 本身不再保存 backorderedAt。
        return order(orderId, ownerId, skuCode, quantity, OrderStatus.PENDING, receivedAt, null, version);
    }

    private static Order order(
            UUID orderId,
            UUID ownerId,
            String skuCode,
            int quantity,
            OrderStatus status,
            Instant receivedAt,
            Instant allocatedAt,
            Long version) {
        return Order.rehydrate(
                orderId,
                ownerId,
                "EXT-" + orderId,
                deliveryTerms(),
                List.of(OrderLine.create(IdGenerator.nextId(), 1, ownerId, skuCode, quantity)),
                status,
                receivedAt,
                null,
                allocatedAt,
                null,
                null,
                version);
    }
}
