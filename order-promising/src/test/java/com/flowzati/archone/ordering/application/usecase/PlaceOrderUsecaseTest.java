package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.util.List;
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

    Order returnedOrder = usecase.placeOrder(new PlaceOrderCommand(
        OrderFixtures.OWNER_ID,
        "EXT-1",
        "100",
        "台北市中正區重慶南路一段 122 號",
        java.time.LocalDate.of(2026, 8, 1),
        OrderFixtures.NODE_ID,
        List.of(new PlaceOrderCommand.Line("SKU-1", 3))));

    verify(repository).save(orderCaptor.capture());
    verify(publisher).publishEvent(eventCaptor.capture());
    verifyNoMoreInteractions(repository, publisher);

    Order persistedOrder = orderCaptor.getValue();
    List<Object> publishedEvents = eventCaptor.getAllValues();
    assertThat(returnedOrder).isSameAs(persistedOrder);
    assertThat(returnedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(publishedEvents).containsExactly(new OrderPlaced(
        returnedOrder.getId(),
        OrderFixtures.OWNER_ID,
        OrderFixtures.NODE_ID,
        "100",
        java.time.LocalDate.of(2026, 8, 1),
        List.of(new LineSnapshot(1, "SKU-1", 3)),
        persistedOrder.getPlacedAt()));
  }
}
