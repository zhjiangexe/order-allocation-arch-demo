package com.flowzati.archone.inventory.balance.application.usecase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.bootstrap.time.ConfiguredBusinessClock;
import com.flowzati.archone.inventory.balance.application.InboundReceiptCompleter;
import com.flowzati.archone.inventory.balance.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.balance.application.event.InventoryEventPublisher;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockFixtures;
import com.flowzati.archone.inventory.balance.domain.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.movement.application.InboundReceiptRegistrar;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.testsupport.MovementFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("確認一段式收貨並發布庫存可用事實")
class ConfirmStockReceiptUsecaseTest {

    private static final String SKU = "SKU-1";
    private static final Instant NOW = Instant.parse("2026-07-21T23:00:00Z");

    private InboundReceiptRegistrar inboundReceiptRegistrar;
    private InboundReceiptCompleter inboundReceiptCompleter;
    private InventoryEventPublisher eventPublisher;
    private ConfirmStockReceiptUsecase usecase;

    @BeforeEach
    void setUp() {
        inboundReceiptRegistrar = mock(InboundReceiptRegistrar.class);
        inboundReceiptCompleter = mock(InboundReceiptCompleter.class);
        eventPublisher = mock(InventoryEventPublisher.class);
        usecase = new ConfirmStockReceiptUsecase(
                new ConfiguredBusinessClock(Clock.fixed(NOW, ZoneId.of("UTC")), "Asia/Taipei"),
                inboundReceiptRegistrar,
                inboundReceiptCompleter,
                eventPublisher);
    }

    @Test
    @DisplayName("同一交易依序記錄、完成 inbound movement，再發布可用庫存事實")
    void shouldCompleteInboundMovementBeforePublishingAvailability() {
        ConfirmStockReceiptCommand command = command();
        StockMove move = inboundMove(command.quantity());
        when(inboundReceiptRegistrar.register(
                        command.facilityId(),
                        command.ownerId(),
                        OrderFixtures.LOCATION_ID,
                        SKU,
                        command.quantity(),
                        NOW))
                .thenReturn(List.of(move));

        usecase.execute(command);

        InOrder order = inOrder(inboundReceiptRegistrar, inboundReceiptCompleter, eventPublisher);
        order.verify(inboundReceiptRegistrar)
                .register(
                        command.facilityId(),
                        command.ownerId(),
                        OrderFixtures.LOCATION_ID,
                        SKU,
                        command.quantity(),
                        NOW);
        order.verify(inboundReceiptCompleter)
                .complete(
                        List.of(move),
                        new InboundReceiptCompleter.BatchIdentity(command.inDate(), command.expiryDate()),
                        NOW);
        order.verify(eventPublisher)
                .publish(new StockAvailabilityIncreased(
                        command.ownerId(),
                        command.facilityId(),
                        OrderFixtures.LOCATION_ID,
                        SKU,
                        command.quantity(),
                        NOW));
    }

    @Test
    @DisplayName("指定位置不屬於 Facility 時拒絕收貨，且不建立任何 movement")
    void shouldRejectALocationOutsideTheFacility() {
        ConfirmStockReceiptCommand command = command();
        when(inboundReceiptRegistrar.register(
                        command.facilityId(),
                        command.ownerId(),
                        command.locationId(),
                        command.sku(),
                        command.quantity(),
                        NOW))
                .thenThrow(new IllegalArgumentException("Stock location " + command.locationId()
                        + " does not belong to facility " + command.facilityId()));

        assertThatThrownBy(() -> usecase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to facility");

        verifyNoInteractions(inboundReceiptCompleter, eventPublisher);
    }

    private ConfirmStockReceiptCommand command() {
        return new ConfirmStockReceiptCommand(
                OrderFixtures.OWNER_ID,
                OrderFixtures.FACILITY_ID,
                OrderFixtures.LOCATION_ID,
                SKU,
                StockFixtures.ARRIVED_ON,
                StockFixtures.EXPIRES_ON,
                10);
    }

    private StockMove inboundMove(int quantity) {
        return StockMove.confirmed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderFixtures.OWNER_ID,
                SKU,
                MovementFixtures.SUPPLIERS_LOCATION_ID,
                OrderFixtures.LOCATION_ID,
                null,
                quantity,
                NOW);
    }
}
