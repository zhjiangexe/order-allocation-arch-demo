package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.ordering.application.command.RecordOrderBackorderCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordOrderBackorderUsecaseTest {

  private final Instant receivedAt = Instant.parse("2026-08-03T00:00:00Z");

  @Test
  @DisplayName("同一缺貨事實以不同 eventId 重送時應維持第一次結果")
  void shouldIgnoreRepeatedBackorderWithADifferentEventId() {
    OrderRepository repository = mock(OrderRepository.class);
    UUID orderId = IdGenerator.nextId();
    Order order = OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt);
    Instant firstBackorderedAt = receivedAt.plusSeconds(10);
    Instant repeatedBackorderedAt = receivedAt.plusSeconds(20);
    when(repository.findById(orderId)).thenReturn(Optional.of(order));
    RecordOrderBackorderUsecase usecase = new RecordOrderBackorderUsecase(repository);

    usecase.execute(new RecordOrderBackorderCommand(orderId, firstBackorderedAt));
    usecase.execute(new RecordOrderBackorderCommand(orderId, repeatedBackorderedAt));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(order.getBackOrderedSince()).isEqualTo(firstBackorderedAt);
    verify(repository, times(2)).findById(orderId);
    verify(repository).save(order);
  }
}
