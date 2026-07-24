package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PlaceOrderUsecaseTest {

  @Test
  @DisplayName("下單時應儲存訂單並發布下單 Domain Event")
  void shouldPersistOrderAndPublishDomainEvent() {
    OrderRepository repository = mock(OrderRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    PlaceOrderUsecase usecase = new PlaceOrderUsecase(repository, publisher);
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

    UUID returnedOrderId = usecase.placeOrder("SKU-1", 3);

    verify(repository).save(orderCaptor.capture());
    verify(publisher).publishEvent(eventCaptor.capture());
    verifyNoMoreInteractions(repository, publisher);

    Order persistedOrder = orderCaptor.getValue();
    List<Object> publishedEvents = eventCaptor.getAllValues();
    assertThat(returnedOrderId).isEqualTo(persistedOrder.getId());
    assertThat(publishedEvents).containsExactly(new OrderPlaced(
        returnedOrderId, "SKU-1", 3, persistedOrder.getPlacedAt()));
  }
}
