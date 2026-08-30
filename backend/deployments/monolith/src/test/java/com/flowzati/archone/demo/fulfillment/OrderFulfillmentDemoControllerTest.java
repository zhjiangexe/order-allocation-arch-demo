package com.flowzati.archone.demo.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import com.flowzati.archone.inventory.movement.entrypoint.StockOperationResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(OrderFulfillmentDemoController.class)
class OrderFulfillmentDemoControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderFulfillmentQueryService queryService;

    @Test
    @DisplayName("履約查詢應以一份 response 組合 source、stock operation、WMS 與 orchestration mode")
    void shouldReturnComposedFulfillmentView() {
        when(queryService.query(ORDER_ID)).thenReturn(view());

        MvcTestResultAssert response = assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$.orchestrationMode").isEqualTo("EVENTS");
        response.bodyJson().extractingPath("$.order.orderId").isEqualTo(ORDER_ID.toString());
        response.bodyJson().extractingPath("$.order.externalOrderNo").isEqualTo("DEMO-001");
        response.bodyJson().extractingPath("$.stockOperation.source.type").isEqualTo("ORDER");
        response.bodyJson()
                .extractingPath("$.stockOperation.moves[0].batches[0].quantity")
                .isEqualTo(5);
        response.bodyJson().doesNotHavePath("$.stockOperation.moves[0].moveLines");
        response.bodyJson().extractingPath("$.shipments.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("不存在的訂單應回 404")
    void shouldReturnNotFoundForUnknownOrder() {
        when(queryService.query(ORDER_ID)).thenThrow(new NoSuchElementException("Order not found: " + ORDER_ID));

        assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID))
                .hasStatus(404);
    }

    private static OrderFulfillmentView view() {
        FulfillmentOrderView order = new FulfillmentOrderView(
                ORDER_ID,
                UUID.fromString("00000000-0000-7000-8000-000000000002"),
                "DEMO-001",
                UUID.fromString("00000000-0000-7000-8000-000000000003"),
                "TW-N",
                "Taipei",
                LocalDate.parse("2026-08-21"),
                Instant.parse("2026-08-20T16:00:00Z"),
                50,
                "PENDING",
                Instant.parse("2026-08-20T08:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());
        Instant enqueuedAt = Instant.parse("2026-08-20T08:00:00Z");
        StockOperationResponse stockOperation = new StockOperationResponse(
                new StockOperationResponse.SourceTrace(MovementSourceType.ORDER, ORDER_ID.toString(), "PRIMARY"),
                new StockOperationResponse.Operation(
                        UUID.fromString("00000000-0000-7000-8000-000000000010"),
                        UUID.fromString("00000000-0000-7000-8000-000000000011"),
                        StockOperationDirection.OUTBOUND,
                        UUID.fromString("00000000-0000-7000-8000-000000000002"),
                        UUID.fromString("00000000-0000-7000-8000-000000000012"),
                        UUID.fromString("00000000-0000-7000-8000-000000000013"),
                        MovementAssignmentPolicy.SHIP_COMPLETE,
                        enqueuedAt,
                        enqueuedAt.plusSeconds(3600),
                        50,
                        StockOperationState.ASSIGNED),
                List.of(new StockOperationResponse.Move(
                        UUID.fromString("00000000-0000-7000-8000-000000000014"),
                        UUID.fromString("00000000-0000-7000-8000-000000000015").toString(),
                        1,
                        "E2E-TEA",
                        5,
                        MoveState.ASSIGNED,
                        enqueuedAt,
                        enqueuedAt.plusSeconds(60),
                        List.of(new StockOperationResponse.Batch(
                                UUID.fromString("00000000-0000-7000-8000-000000000016"),
                                UUID.fromString("00000000-0000-7000-8000-000000000012"),
                                "E2E-TEA",
                                LocalDate.parse("2026-08-01"),
                                LocalDate.parse("2026-09-01"),
                                5)))));
        return new OrderFulfillmentView("EVENTS", order, stockOperation, List.of(), null);
    }
}
