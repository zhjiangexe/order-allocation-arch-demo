package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.ordering.application.command.RecordOrderAllocationCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordOrderAllocationUsecaseTest {

  @Test
  @DisplayName("補到貨後應將缺貨訂單記錄為已配置")
  void shouldRecordBackorderedOrderAsAllocated() {
    Instant receivedAt = Instant.parse("2026-08-03T00:00:00Z");
    Instant backorderedAt = receivedAt.plusSeconds(10);
    Instant allocatedAt = receivedAt.plusSeconds(20);
    UUID orderId = IdGenerator.nextId();
    Order order = OrderFixtures.pendingOrder(orderId, "SKU-1", 3, receivedAt);
    order.markBackOrdered(backorderedAt);
    OrderRepository repository = mock(OrderRepository.class);
    when(repository.findById(orderId)).thenReturn(Optional.of(order));
    RecordOrderAllocationUsecase usecase = new RecordOrderAllocationUsecase(repository);

    usecase.execute(new RecordOrderAllocationCommand(orderId, allocatedAt));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
    verify(repository).save(order);
  }
}
