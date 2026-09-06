package com.flowzati.archone.orderfulfillment.orchestration.eventdriven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationRequest;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventDrivenFulfillmentCancellationCoordinatorTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID SHIPMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    private GetOrderUsecase getOrderUsecase;
    private GetOrderShipmentsUsecase getOrderShipmentsUsecase;
    private CancelShipmentUsecase cancelShipmentUsecase;
    private CancelOrderUsecase cancelOrderUsecase;
    private EventDrivenFulfillmentCancellationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        getOrderUsecase = mock(GetOrderUsecase.class);
        getOrderShipmentsUsecase = mock(GetOrderShipmentsUsecase.class);
        cancelShipmentUsecase = mock(CancelShipmentUsecase.class);
        cancelOrderUsecase = mock(CancelOrderUsecase.class);
        coordinator = new EventDrivenFulfillmentCancellationCoordinator(
                getOrderUsecase, getOrderShipmentsUsecase, cancelShipmentUsecase, cancelOrderUsecase);

        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.ALLOCATED);
        when(getOrderUsecase.getOrder(ORDER_ID)).thenReturn(order);
    }

    @Test
    @DisplayName("events mode 受理 WMS 取消後必須等待 Shipment 終態事件才取消 Order")
    void shouldAwaitShipmentTerminalEventBeforeCancellingOrder() {
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(SHIPMENT_ID);
        when(getOrderShipmentsUsecase.query(ORDER_ID)).thenReturn(List.of(shipment));
        when(cancelShipmentUsecase.handle(any())).thenReturn(CancelShipmentStatus.ACCEPTED);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.ACCEPTED);
        verify(cancelShipmentUsecase).handle(any());
        verifyNoInteractions(cancelOrderUsecase);
    }

    @Test
    @DisplayName("WMS recovery 尚未完成時仍回受理，但不可先把 Order 取消")
    void shouldAcceptDeferredCancellationWithoutCancellingOrder() {
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(SHIPMENT_ID);
        when(getOrderShipmentsUsecase.query(ORDER_ID)).thenReturn(List.of(shipment));
        when(cancelShipmentUsecase.handle(any())).thenReturn(CancelShipmentStatus.ALREADY_ACCEPTED);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.ACCEPTED);
        verifyNoInteractions(cancelOrderUsecase);
    }

    @Test
    @DisplayName("Shipment 已 handover 時維持拒絕，交給未來 return flow")
    void shouldRejectCancellationAfterHandover() {
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(SHIPMENT_ID);
        when(getOrderShipmentsUsecase.query(ORDER_ID)).thenReturn(List.of(shipment));
        when(cancelShipmentUsecase.handle(any())).thenReturn(CancelShipmentStatus.REJECTED);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.REJECTED);
        assertThat(result.detail()).contains("handed over");
        verifyNoInteractions(cancelOrderUsecase);
    }

    private static FulfillmentCancellationRequest request() {
        return new FulfillmentCancellationRequest(REQUEST_ID, ORDER_ID, REQUESTED_AT, "Customer changed mind");
    }
}
