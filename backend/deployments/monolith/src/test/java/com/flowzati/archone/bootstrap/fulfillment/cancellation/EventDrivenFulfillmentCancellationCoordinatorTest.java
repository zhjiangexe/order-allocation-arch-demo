package com.flowzati.archone.bootstrap.fulfillment.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.wms.outbound.application.query.ShipmentView;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentCancellationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
    @DisplayName("events mode 必須先取得 WMS 取消決策，確認安全後才取消 Order")
    void shouldCancelShipmentBeforeOrder() {
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(SHIPMENT_ID);
        when(getOrderShipmentsUsecase.query(ORDER_ID)).thenReturn(List.of(shipment));
        when(cancelShipmentUsecase.handle(any())).thenReturn(ShipmentCancellationStatus.CANCELLED);
        when(cancelOrderUsecase.cancel(any())).thenReturn(Order.CancellationStatus.CANCELLED);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.ACCEPTED);
        InOrder order = inOrder(cancelShipmentUsecase, cancelOrderUsecase);
        order.verify(cancelShipmentUsecase).handle(any());
        order.verify(cancelOrderUsecase).cancel(any());
    }

    @Test
    @DisplayName("WMS 要求實體 putback 時不可先把 Order 取消")
    void shouldKeepOrderWhenPhysicalPutbackIsRequired() {
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(SHIPMENT_ID);
        when(getOrderShipmentsUsecase.query(ORDER_ID)).thenReturn(List.of(shipment));
        when(cancelShipmentUsecase.handle(any())).thenReturn(ShipmentCancellationStatus.PUTBACK_REQUIRED);

        FulfillmentCancellationResult result = coordinator.request(request());

        assertThat(result.status()).isEqualTo(FulfillmentCancellationStatus.REJECTED);
        assertThat(result.detail()).contains("putback");
        verifyNoInteractions(cancelOrderUsecase);
    }

    private static FulfillmentCancellationRequest request() {
        return new FulfillmentCancellationRequest(REQUEST_ID, ORDER_ID, REQUESTED_AT, "Customer changed mind");
    }
}
