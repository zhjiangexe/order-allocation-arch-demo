package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
  @DisplayName("取消訂單時應儲存狀態並發布取消 Domain Event")
  void shouldPersistCancelledOrderAndPublishDomainEvent() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", 3, placedAt);
    order.releaseDomainEvents();
    when(repository.findById(order.getId())).thenReturn(Optional.of(order));

    new CancelOrderUsecase(repository, publisher).cancel(order.getId(), cancelledAt);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    verify(repository).save(order);
    // 取消事件不帶行——它要說的是「哪張單、什麼時候」，行的內容不構成這個事實的一部分。
    verify(publisher).publishEvent(new OrderCancelled(
        order.getId(), OrderFixtures.OWNER_ID, OrderFixtures.NODE_ID, cancelledAt));
  }

  @Test
  @DisplayName("訂單已取消時應為合法 no-op")
  void shouldDoNothingWhenOrderIsAlreadyCancelled() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    Order order = OrderFixtures.pendingOrder(UUID.randomUUID(), "SKU-1", 3, placedAt);
    order.cancel(cancelledAt);
    order.releaseDomainEvents();
    when(repository.findById(order.getId())).thenReturn(Optional.of(order));

    new CancelOrderUsecase(repository, publisher).cancel(order.getId(), cancelledAt.plusSeconds(1));

    verifyNoInteractions(publisher);
    verify(repository).findById(order.getId());
  }

  @Test
  @DisplayName("找不到訂單時取消應失敗")
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
