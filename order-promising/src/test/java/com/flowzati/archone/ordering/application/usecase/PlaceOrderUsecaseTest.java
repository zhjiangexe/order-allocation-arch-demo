package com.flowzati.archone.ordering.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.flowzati.archone.bootstrap.time.ConfiguredBusinessClock;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.event.OrderingDomainEventPublisher;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlaceOrderUsecaseTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-26T08:00:00Z");

    @Test
    @DisplayName("下單時應儲存訂單並發布下單 Domain Event")
    void shouldPersistOrderAndPublishDomainEvent() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderingDomainEventPublisher publisher = mock(OrderingDomainEventPublisher.class);
        PlaceOrderUsecase usecase = new PlaceOrderUsecase(repository, fixedClock(), publisher);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);

        Order returnedOrder = usecase.placeOrder(new PlaceOrderCommand(
                OrderFixtures.OWNER_ID,
                "EXT-1",
                "100",
                "台北市中正區重慶南路一段 122 號",
                java.time.LocalDate.of(2026, 8, 1),
                OrderFixtures.DISPATCH_BY,
                OrderFixtures.RELEASE_PRIORITY,
                OrderFixtures.FACILITY_ID,
                null,
                List.of(new PlaceOrderCommand.Line("SKU-1", 3))));

        verify(repository).save(orderCaptor.capture());
        Order persistedOrder = orderCaptor.getValue();
        assertThat(returnedOrder).isSameAs(persistedOrder);
        assertThat(returnedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(publisher)
                .publishAll(List.of(new OrderPlaced(
                        returnedOrder.getId(),
                        OrderFixtures.OWNER_ID,
                        OrderFixtures.FACILITY_ID,
                        "100",
                        java.time.LocalDate.of(2026, 8, 1),
                        List.of(new LineSnapshot(1, "SKU-1", 3)),
                        // 事件帶的是收單時刻——它描述「這件事在我們系統裡何時發生」。
                        persistedOrder.getReceivedAt())));
        verifyNoMoreInteractions(repository, publisher);

        // 收單時刻由 usecase 以系統時鐘寫入，不接受呼叫端提供；命令沒給上游下單時刻，訂單就
        // 不帶它，而不是被補成收單時刻。
        assertThat(persistedOrder.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(persistedOrder.getPlacedAt()).isNull();
    }

    @Test
    @DisplayName("命令帶上游下單時刻時原樣保留，且與收單時刻各自獨立")
    void shouldPreserveUpstreamPlacedTime() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderingDomainEventPublisher publisher = mock(OrderingDomainEventPublisher.class);
        PlaceOrderUsecase usecase = new PlaceOrderUsecase(repository, fixedClock(), publisher);
        Instant upstreamPlacedAt = Instant.parse("2026-07-26T06:30:00Z");

        Order order = usecase.placeOrder(new PlaceOrderCommand(
                OrderFixtures.OWNER_ID,
                "EXT-2",
                "100",
                "台北市中正區重慶南路一段 122 號",
                java.time.LocalDate.of(2026, 8, 1),
                OrderFixtures.DISPATCH_BY,
                OrderFixtures.RELEASE_PRIORITY,
                OrderFixtures.FACILITY_ID,
                upstreamPlacedAt,
                List.of(new PlaceOrderCommand.Line("SKU-1", 3))));

        assertThat(order.getPlacedAt()).isEqualTo(upstreamPlacedAt);
        assertThat(order.getReceivedAt()).isAfter(upstreamPlacedAt);
    }

    private static ConfiguredBusinessClock fixedClock() {
        return new ConfiguredBusinessClock(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC), "Asia/Taipei");
    }
}
