package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.exception.OrderFulfillmentConflictException;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.testsupport.OrderingFixtures;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordOrderFulfillmentUsecaseTest {

    @Test
    @DisplayName("出庫完成後應將已配置訂單記錄為 FULFILLED，重送不得覆寫時間")
    void recordsFulfillmentIdempotently() {
        Instant receivedAt = Instant.parse("2026-08-13T00:00:00Z");
        Instant allocatedAt = receivedAt.plusSeconds(10);
        Instant fulfilledAt = receivedAt.plusSeconds(20);
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Order order = OrderingFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt);
        order.markAllocated(allocatedAt);
        OrderStore repository = mock(OrderStore.class);
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        RecordOrderFulfillmentUsecase usecase = new RecordOrderFulfillmentUsecase(repository);

        usecase.execute(new RecordOrderFulfillmentCommand(orderId, shipmentId, fulfilledAt));
        usecase.execute(new RecordOrderFulfillmentCommand(orderId, shipmentId, fulfilledAt));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.getFulfilledAt()).isEqualTo(fulfilledAt);
        assertThat(order.getFulfilledByShipmentId()).isEqualTo(shipmentId);
        verify(repository, times(1)).save(order);
    }

    @Test
    @DisplayName("已由另一張 Shipment 履約的訂單應拒絕衝突事實")
    void rejectsConflictingShipment() {
        Instant receivedAt = Instant.parse("2026-08-13T00:00:00Z");
        Instant fulfilledAt = receivedAt.plusSeconds(20);
        UUID orderId = UUID.randomUUID();
        Order order = OrderingFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt);
        order.markAllocated(receivedAt.plusSeconds(10));
        order.markFulfilled(UUID.randomUUID(), fulfilledAt);
        OrderStore repository = mock(OrderStore.class);
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> new RecordOrderFulfillmentUsecase(repository)
                        .execute(new RecordOrderFulfillmentCommand(orderId, UUID.randomUUID(), fulfilledAt)))
                .isInstanceOf(OrderFulfillmentConflictException.class)
                .hasMessageContaining("different immutable fact");
    }

    @Test
    @DisplayName("找不到訂單時應失敗，避免 Workflow 誤判已記錄完成")
    void failsWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        OrderStore repository = mock(OrderStore.class);
        when(repository.findById(orderId)).thenReturn(Optional.empty());
        RecordOrderFulfillmentUsecase usecase = new RecordOrderFulfillmentUsecase(repository);

        assertThatThrownBy(() -> usecase.execute(new RecordOrderFulfillmentCommand(
                        orderId, UUID.randomUUID(), Instant.parse("2026-08-13T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Order not found");
    }
}
