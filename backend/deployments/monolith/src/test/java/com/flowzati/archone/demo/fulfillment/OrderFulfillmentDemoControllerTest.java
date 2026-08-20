package com.flowzati.archone.demo.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
    @DisplayName("履約查詢應以一份 response 組合 Order、Allocation、WMS 與 orchestration mode")
    void shouldReturnComposedFulfillmentView() {
        when(queryService.query(ORDER_ID)).thenReturn(view());

        MvcTestResultAssert response = assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$.orchestrationMode").isEqualTo("EVENTS");
        response.bodyJson().extractingPath("$.order.orderId").isEqualTo(ORDER_ID.toString());
        response.bodyJson().extractingPath("$.order.externalOrderNo").isEqualTo("DEMO-001");
        response.bodyJson().extractingPath("$.allocation").isNull();
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
        return new OrderFulfillmentView("EVENTS", order, null, List.of(), null);
    }
}
