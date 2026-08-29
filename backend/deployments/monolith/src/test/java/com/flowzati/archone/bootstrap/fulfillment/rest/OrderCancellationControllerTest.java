package com.flowzati.archone.bootstrap.fulfillment.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationCoordinator;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationRequest;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationResult;
import com.flowzati.archone.process.fulfillment.cancellation.FulfillmentCancellationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(OrderCancellationController.class)
class OrderCancellationControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private FulfillmentCancellationCoordinator coordinator;

    @Test
    @DisplayName("協調者受理取消時 HTTP 應回 202，而不是假裝補償已全部完成")
    void shouldAcceptCancellationRequest() {
        when(coordinator.request(any()))
                .thenReturn(new FulfillmentCancellationResult(
                        FulfillmentCancellationStatus.ACCEPTED, REQUEST_ID, "Order cancellation was committed"));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(202);
        response.bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");
        response.bodyJson().extractingPath("$.effectiveRequestId").isEqualTo(REQUEST_ID.toString());
        verify(coordinator)
                .request(new FulfillmentCancellationRequest(
                        REQUEST_ID, ORDER_ID, REQUESTED_AT, "Customer changed mind"));
    }

    @Test
    @DisplayName("倉內作業已開始而拒絕取消時應回 409 與可解釋結果")
    void shouldReportRejectedCancellationAsConflict() {
        when(coordinator.request(any()))
                .thenReturn(new FulfillmentCancellationResult(
                        FulfillmentCancellationStatus.REJECTED, REQUEST_ID, "Physical putback is required"));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(409);
        response.bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        response.bodyJson().extractingPath("$.detail").isEqualTo("Physical putback is required");
    }

    @Test
    @DisplayName("requestId、requestedAt 與 reason 缺少任何一項都不應進入協調流程")
    void shouldRejectAnIncompleteImmutableRequest() {
        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + REQUEST_ID + "\",\"reason\":\"Customer changed mind\"}"));

        response.hasStatus(400);
        response.bodyJson().extractingPath("$.type").isEqualTo("urn:archone:problem:request-validation");
        response.bodyJson().extractingPath("$.errors[0].field").isEqualTo("requestedAt");
        response.bodyJson().extractingPath("$.errors[0].code").isEqualTo("NotNull");
        response.bodyJson().extractingPath("$.errors[0].message").isEqualTo("Cancellation request time is required");

        verifyNoInteractions(coordinator);
    }

    private static String requestBody() {
        return """
          {
            "requestId": "%s",
            "requestedAt": "%s",
            "reason": "Customer changed mind"
          }
          """.formatted(REQUEST_ID, REQUESTED_AT);
    }
}
