package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
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
    Order order = OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt);
    order.markAllocated(allocatedAt);
    OrderRepository repository = mock(OrderRepository.class);
    when(repository.findById(orderId)).thenReturn(Optional.of(order));
    RecordOrderFulfillmentUsecase usecase = new RecordOrderFulfillmentUsecase(repository);

    usecase.execute(new RecordOrderFulfillmentCommand(orderId, fulfilledAt));
    usecase.execute(new RecordOrderFulfillmentCommand(orderId, fulfilledAt.plusSeconds(30)));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
    assertThat(order.getFulfilledAt()).isEqualTo(fulfilledAt);
    verify(repository, times(1)).save(order);
  }

  @Test
  @DisplayName("找不到訂單時應失敗，避免 Workflow 誤判已記錄完成")
  void failsWhenOrderDoesNotExist() {
    UUID orderId = UUID.randomUUID();
    OrderRepository repository = mock(OrderRepository.class);
    when(repository.findById(orderId)).thenReturn(Optional.empty());
    RecordOrderFulfillmentUsecase usecase = new RecordOrderFulfillmentUsecase(repository);

    assertThatThrownBy(() -> usecase.execute(new RecordOrderFulfillmentCommand(
        orderId, Instant.parse("2026-08-13T00:00:00Z"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Order not found");
  }
}
