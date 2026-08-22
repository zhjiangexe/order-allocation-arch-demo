package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderingPartitionKeyResolver;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.testsupport.OrderingFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlaceOrderUsecaseTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-26T08:00:00Z");

    @Test
    @DisplayName("下單時應儲存訂單並發布下單 Integration Event")
    void shouldPersistOrderAndPublishIntegrationEvent() {
        OrderRepository repository = mock(OrderRepository.class);
        List<IntegrationEventPublication> publications = new ArrayList<>();
        IntegrationEventPublisher publisher = publications::add;
        PlaceOrderUsecase usecase = new PlaceOrderUsecase(
                repository, fixedClock(), publisher, new OrderingPartitionKeyResolver("order-id"));
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);

        Order returnedOrder = usecase.placeOrder(new PlaceOrderCommand(
                OrderingFixtures.OWNER_ID,
                "EXT-1",
                "100",
                "台北市中正區重慶南路一段 122 號",
                java.time.LocalDate.of(2026, 8, 1),
                OrderingFixtures.DISPATCH_BY,
                OrderingFixtures.RELEASE_PRIORITY,
                OrderingFixtures.FACILITY_ID,
                null,
                List.of(new PlaceOrderCommand.Line("SKU-1", 3))));

        verify(repository).save(orderCaptor.capture());
        Order persistedOrder = orderCaptor.getValue();
        assertThat(returnedOrder).isSameAs(persistedOrder);
        assertThat(returnedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(publications).singleElement().satisfies(publication -> {
            assertThat(publication.event()).isInstanceOf(OrderPlacedIntegrationEvent.class);
            assertThat(((OrderPlacedIntegrationEvent) publication.event()).getOrderId())
                    .isEqualTo(returnedOrder.getId());
            assertThat(publication.target().partitionKey())
                    .isEqualTo(returnedOrder.getId().toString());
            assertThat(publication.occurredAt()).isEqualTo(RECEIVED_AT);
        });
        verifyNoMoreInteractions(repository);

        // 收單時刻由 usecase 以系統時鐘寫入，不接受呼叫端提供；命令沒給上游下單時刻，訂單就
        // 不帶它，而不是被補成收單時刻。
        assertThat(persistedOrder.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(persistedOrder.getPlacedAt()).isNull();
    }

    @Test
    @DisplayName("命令帶上游下單時刻時原樣保留，且與收單時刻各自獨立")
    void shouldPreserveUpstreamPlacedTime() {
        OrderRepository repository = mock(OrderRepository.class);
        IntegrationEventPublisher publisher = publication -> {};
        PlaceOrderUsecase usecase = new PlaceOrderUsecase(
                repository, fixedClock(), publisher, new OrderingPartitionKeyResolver("order-id"));
        Instant upstreamPlacedAt = Instant.parse("2026-07-26T06:30:00Z");

        Order order = usecase.placeOrder(new PlaceOrderCommand(
                OrderingFixtures.OWNER_ID,
                "EXT-2",
                "100",
                "台北市中正區重慶南路一段 122 號",
                java.time.LocalDate.of(2026, 8, 1),
                OrderingFixtures.DISPATCH_BY,
                OrderingFixtures.RELEASE_PRIORITY,
                OrderingFixtures.FACILITY_ID,
                upstreamPlacedAt,
                List.of(new PlaceOrderCommand.Line("SKU-1", 3))));

        assertThat(order.getPlacedAt()).isEqualTo(upstreamPlacedAt);
        assertThat(order.getReceivedAt()).isAfter(upstreamPlacedAt);
    }

    private static BusinessClock fixedClock() {
        return new BusinessClock() {
            @Override
            public LocalDate today() {
                return LocalDate.of(2026, 7, 26);
            }

            @Override
            public Instant instant() {
                return RECEIVED_AT;
            }
        };
    }
}
