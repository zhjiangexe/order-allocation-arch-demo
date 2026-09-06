package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.ordering.application.event.OrderCancelled;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.port.OrderCancelledPublisher;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.testsupport.OrderingFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelOrderUsecaseTest {

    private final Instant receivedAt = Instant.parse("2026-07-24T00:00:00Z");
    private final Instant cancelledAt = Instant.parse("2026-07-24T01:00:00Z");
    private final UUID requestId = UUID.randomUUID();
    private final String reason = "Customer requested cancellation";

    @Test
    @DisplayName("取消訂單時應儲存狀態並發布取消 Integration Event")
    void shouldPersistCancelledOrderAndPublishIntegrationEvent() {
        OrderStore repository = mock(OrderStore.class);
        List<OrderCancelled> events = new ArrayList<>();
        Order order = OrderingFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", 3, receivedAt);
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));

        CancelOrderUsecase usecase = new CancelOrderUsecase(repository, events::add);
        CancelOrderCommand command = new CancelOrderCommand(requestId, order.getId(), cancelledAt, reason);

        assertThat(usecase.cancel(command)).isEqualTo(Order.CancellationStatus.CANCELLED);
        assertThat(usecase.cancel(command)).isEqualTo(Order.CancellationStatus.ALREADY_CANCELLED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationRequestId()).isEqualTo(requestId);
        assertThat(order.getCancellationReason()).isEqualTo(reason);
        verify(repository).save(order);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.orderId()).isEqualTo(order.getId());
            assertThat(event.ownerId()).isEqualTo(order.getOwnerId());
            assertThat(event.facilityId()).isEqualTo(order.getDeliveryTerms().facilityId());
            assertThat(event.cancelledAt()).isEqualTo(cancelledAt);
        });
    }

    @Test
    @DisplayName("訂單已取消時應為合法 no-op")
    void shouldDoNothingWhenOrderIsAlreadyCancelled() {
        OrderStore repository = mock(OrderStore.class);
        OrderCancelledPublisher publisher = mock(OrderCancelledPublisher.class);
        Order order = OrderingFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", 3, receivedAt);
        order.cancel(requestId, cancelledAt, reason);
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThat(new CancelOrderUsecase(repository, publisher)
                        .cancel(new CancelOrderCommand(requestId, order.getId(), cancelledAt, reason)))
                .isEqualTo(Order.CancellationStatus.ALREADY_CANCELLED);

        verifyNoInteractions(publisher);
        verify(repository).findById(order.getId());
    }

    @Test
    @DisplayName("訂單已由另一筆 immutable request 取消時應拒絕")
    void shouldRejectAnotherCancellationRequest() {
        OrderStore repository = mock(OrderStore.class);
        OrderCancelledPublisher publisher = mock(OrderCancelledPublisher.class);
        Order order = OrderingFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", 3, receivedAt);
        order.cancel(requestId, cancelledAt, reason);
        when(repository.findById(order.getId())).thenReturn(Optional.of(order));

        CancelOrderCommand conflicting = new CancelOrderCommand(UUID.randomUUID(), order.getId(), cancelledAt, reason);

        assertThatThrownBy(() -> new CancelOrderUsecase(repository, publisher).cancel(conflicting))
                .isInstanceOfSatisfying(
                        DomainConflictException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(OrderErrorCode.CANCELLATION_REQUEST_CONFLICT))
                .hasMessageContaining("different immutable request");
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("找不到訂單時取消應失敗")
    void shouldFailWhenOrderDoesNotExist() {
        OrderStore repository = mock(OrderStore.class);
        OrderCancelledPublisher publisher = mock(OrderCancelledPublisher.class);
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        CancelOrderCommand command = new CancelOrderCommand(requestId, orderId, cancelledAt, reason);
        assertThatThrownBy(() -> new CancelOrderUsecase(repository, publisher).cancel(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Order not found: " + orderId);
        verifyNoInteractions(publisher);
    }
}
