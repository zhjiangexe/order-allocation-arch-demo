package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CancelOrderUsecaseTest {

  private final Instant placedAt = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant cancelledAt = Instant.parse("2026-07-24T01:00:00Z");

  @Test
  void shouldPersistCancelledOrderAndPublishDomainEvent() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    Order order = Order.place(UUID.randomUUID(), "SKU-1", 3, placedAt);
    order.releaseDomainEvents();
    when(repository.findById(order.getId())).thenReturn(Optional.of(order));

    new CancelOrderUsecase(repository, publisher).cancel(order.getId(), cancelledAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    verify(repository).save(order);
    verify(publisher).publishEvent(new OrderCancelled(order.getId(), cancelledAt));
  }

  @Test
  void shouldDoNothingWhenOrderIsAlreadyCancelled() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    Order order = Order.place(UUID.randomUUID(), "SKU-1", 3, placedAt);
    order.cancel(cancelledAt);
    order.releaseDomainEvents();
    when(repository.findById(order.getId())).thenReturn(Optional.of(order));

    new CancelOrderUsecase(repository, publisher).cancel(order.getId(), cancelledAt.plusSeconds(1));

    verifyNoInteractions(publisher);
    verify(repository).findById(order.getId());
  }

  @Test
  void shouldFailWhenOrderDoesNotExist() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    UUID orderId = UUID.randomUUID();
    when(repository.findById(orderId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> new CancelOrderUsecase(repository, publisher).cancel(orderId, cancelledAt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Order not found: " + orderId);
    verifyNoInteractions(publisher);
  }
}
