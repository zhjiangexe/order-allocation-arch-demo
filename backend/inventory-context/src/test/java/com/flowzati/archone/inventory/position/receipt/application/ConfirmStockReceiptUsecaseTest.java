package com.flowzati.archone.inventory.position.receipt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.service.InboundReceiptRegistrar;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.position.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.position.application.event.StockAvailabilityIncreased;
import com.flowzati.archone.inventory.position.application.port.StockAvailabilityIncreasedPublisher;
import com.flowzati.archone.inventory.position.application.service.InboundReceiptCompleter;
import com.flowzati.archone.inventory.position.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.inventory.position.domain.valueobject.ReceivingBatchIdentity;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("確認一段式收貨並發布庫存可用事實")
class ConfirmStockReceiptUsecaseTest {

    private static final String SKU = "SKU-1";
    private static final Instant NOW = Instant.parse("2026-07-21T23:00:00Z");

    private InboundReceiptRegistrar inboundReceiptRegistrar;
    private InboundReceiptCompleter inboundReceiptCompleter;
    private StockAvailabilityIncreasedPublisher availabilityPublisher;
    private ConfirmStockReceiptUsecase usecase;

    @BeforeEach
    void setUp() {
        inboundReceiptRegistrar = mock(InboundReceiptRegistrar.class);
        inboundReceiptCompleter = mock(InboundReceiptCompleter.class);
        availabilityPublisher = mock(StockAvailabilityIncreasedPublisher.class);
        usecase = new ConfirmStockReceiptUsecase(
                InventoryFixtures.businessClock(Clock.fixed(NOW, ZoneId.of("UTC")), "Asia/Taipei"),
                inboundReceiptRegistrar,
                inboundReceiptCompleter,
                availabilityPublisher);
    }

    @Test
    @DisplayName("同一交易依序記錄、完成 inbound movement，再發布可用庫存事實")
    void shouldCompleteInboundMovementBeforePublishingAvailability() {
        ConfirmStockReceiptCommand command = command();
        StockMove move = inboundMove(command.quantity());
        when(inboundReceiptRegistrar.register(
                        command.facilityId(),
                        command.ownerId(),
                        InventoryFixtures.LOCATION_ID,
                        SKU,
                        command.quantity(),
                        NOW))
                .thenReturn(List.of(move));

        usecase.execute(command);

        InOrder order = inOrder(inboundReceiptRegistrar, inboundReceiptCompleter, availabilityPublisher);
        order.verify(inboundReceiptRegistrar)
                .register(
                        command.facilityId(),
                        command.ownerId(),
                        InventoryFixtures.LOCATION_ID,
                        SKU,
                        command.quantity(),
                        NOW);
        order.verify(inboundReceiptCompleter)
                .complete(List.of(move), new ReceivingBatchIdentity(command.inDate(), command.expiryDate()), NOW);
        ArgumentCaptor<StockAvailabilityIncreased> increase = ArgumentCaptor.forClass(StockAvailabilityIncreased.class);
        order.verify(availabilityPublisher).publish(increase.capture());
        assertThat(increase.getValue())
                .isEqualTo(new StockAvailabilityIncreased(
                        command.ownerId(),
                        command.facilityId(),
                        InventoryFixtures.LOCATION_ID,
                        command.sku(),
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

        verifyNoInteractions(inboundReceiptCompleter, availabilityPublisher);
    }

    private ConfirmStockReceiptCommand command() {
        return new ConfirmStockReceiptCommand(
                InventoryFixtures.OWNER_ID,
                InventoryFixtures.FACILITY_ID,
                InventoryFixtures.LOCATION_ID,
                SKU,
                StockFixtures.ARRIVED_ON,
                StockFixtures.EXPIRES_ON,
                10);
    }

    private StockMove inboundMove(int quantity) {
        return StockMove.confirmed(
                UUID.randomUUID(),
                UUID.randomUUID(),
                InventoryFixtures.OWNER_ID,
                SKU,
                InventoryFixtures.SUPPLIERS_LOCATION_ID,
                InventoryFixtures.LOCATION_ID,
                quantity,
                NOW);
    }
}
