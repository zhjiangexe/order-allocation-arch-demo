package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.error.NotFoundException;
import com.flowzati.archone.ordering.application.error.OrderApplicationErrorCode;
import com.flowzati.archone.ordering.application.store.OrderStore;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.testsupport.OrderingFixtures;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetOrderUsecaseTest {

    private final OrderStore repository = mock(OrderStore.class);
    private final GetOrderUsecase usecase = new GetOrderUsecase(repository);

    @Test
    @DisplayName("查詢存在的訂單時應回傳該訂單")
    void shouldReturnOrderWhenFound() {
        UUID orderId = UUID.randomUUID();
        Order order = OrderingFixtures.pendingOrder(orderId, "SKU-1", 3, Instant.now());
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        assertThat(usecase.getOrder(orderId)).isEqualTo(order);
    }

    @Test
    @DisplayName("查詢不存在的訂單時應丟出帶有穩定代碼的 NotFoundException")
    void shouldThrowWhenOrderMissing() {
        UUID orderId = UUID.randomUUID();
        when(repository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usecase.getOrder(orderId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(orderId.toString())
                .satisfies(exception -> assertThat(((NotFoundException) exception).errorCode())
                        .isEqualTo(OrderApplicationErrorCode.ORDER_NOT_FOUND));
    }
}
