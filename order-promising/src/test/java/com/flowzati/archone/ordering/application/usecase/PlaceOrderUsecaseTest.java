package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PlaceOrderUsecaseTest {

  @Test
  void shouldPersistOrderAndPublishDomainThenIntegrationEvent() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    PlaceOrderUsecase usecase = new PlaceOrderUsecase(repository, publisher);
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

    UUID returnedOrderId = usecase.placeOrder("SKU-1", 3);

    verify(repository).save(orderCaptor.capture());
    verify(publisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
    verifyNoMoreInteractions(repository, publisher);

    Order persistedOrder = orderCaptor.getValue();
    List<Object> publishedEvents = eventCaptor.getAllValues();
    assertThat(returnedOrderId).isEqualTo(persistedOrder.getId());
    assertThat(publishedEvents.get(0)).isEqualTo(new OrderPlaced(
        returnedOrderId, "SKU-1", 3, persistedOrder.getPlacedAt()));
    assertThat(publishedEvents.get(1))
        .isInstanceOfSatisfying(OrderPlacedIntegrationEvent.class, event -> {
          assertThat(event.getEventId()).isNotNull();
          assertThat(event.getOrderId()).isEqualTo(returnedOrderId);
          assertThat(event.getPlacedAt()).isEqualTo(persistedOrder.getPlacedAt());
        });
  }
}
