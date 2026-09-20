package com.flowzati.archone.ordering.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationRequest;
import com.flowzati.archone.ordering.application.invocation.GetOrderQuery;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderCancellationRpcRestTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");
    private static final String REASON = "Customer changed mind";

    private final GetOrderUsecase getOrderUsecase = mock(GetOrderUsecase.class);
    private final OrderCancellationRpcRest rpc = new OrderCancellationRpcRest(getOrderUsecase);

    @Test
    void assessmentPreservesCommittedRequestConflict() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancellationRequestId()).thenReturn(UUID.randomUUID());
        when(getOrderUsecase.getOrder(new GetOrderQuery(ORDER_ID))).thenReturn(order);

        assertThatThrownBy(() -> rpc.assess(request())).isInstanceOf(DomainConflictException.class);
    }

    @Test
    void assessmentRecognizesSameCommittedRequest() {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancellationRequestId()).thenReturn(REQUEST_ID);
        when(order.getCancellationReason()).thenReturn(REASON);
        when(getOrderUsecase.getOrder(new GetOrderQuery(ORDER_ID))).thenReturn(order);

        assertThat(rpc.assess(request())).isEqualTo(OrderCancellationAssessment.ALREADY_CANCELLED);
    }

    @Test
    void rpcMappingIsAvailableOverHttp() throws Exception {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(getOrderUsecase.getOrder(new GetOrderQuery(ORDER_ID))).thenReturn(order);

        MockMvcBuilders.standaloneSetup(rpc)
                .build()
                .perform(post("/internal/ordering/cancellation/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"requestId":"00000000-0000-7000-8000-000000000002",
                             "orderId":"00000000-0000-7000-8000-000000000001",
                             "requestedAt":"2026-08-20T08:00:00Z",
                             "reason":"Customer changed mind"}
                            """))
                .andExpect(status().isOk())
                .andExpect(content().string("\"CANCELLABLE\""));
    }

    private static OrderCancellationRequest request() {
        return new OrderCancellationRequest(REQUEST_ID, ORDER_ID, REQUESTED_AT, REASON);
    }
}
