package com.flowzati.archone.orderfulfillment.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.application.CancellationRequestCoordinator;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.support.spring.web.validation.GlobalRestExceptionHandler;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@WebMvcTest(OrderCancellationRest.class)
@Import({GlobalRestExceptionHandler.class, CancellationRpcExceptionHandler.class})
@TestPropertySource(
        properties = "archone.web.validation.message-basenames="
                + "classpath:i18n/validation/constraints_template,"
                + "classpath:i18n/validation/problem_detail,"
                + "classpath:i18n/fulfillment/request_field")
class OrderCancellationRestTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CancellationRequestCoordinator coordinator;

    @Test
    @DisplayName("協調者受理取消時 HTTP 應回 202，而不是假裝補償已全部完成")
    void shouldAcceptCancellationRequest() {
        when(coordinator.request(any()))
                .thenReturn(new FulfillmentCancellationResult(FulfillmentCancellationStatus.ACCEPTED, REQUEST_ID));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(202);
        response.bodyJson().extractingPath("$.status").isEqualTo("ACCEPTED");
        response.bodyJson().extractingPath("$.effectiveRequestId").isEqualTo(REQUEST_ID.toString());
        response.bodyJson().doesNotHavePath("$.detail");
        verify(coordinator)
                .request(new FulfillmentCancellationCommand(
                        REQUEST_ID, ORDER_ID, REQUESTED_AT, "Customer changed mind"));
    }

    @ParameterizedTest
    @EnumSource(
            value = FulfillmentCancellationStatus.class,
            names = {"REJECTED", "CONFLICT"})
    void shouldReportRejectedOrConflictingCancellationAsHttp409(FulfillmentCancellationStatus status) {
        when(coordinator.request(any())).thenReturn(new FulfillmentCancellationResult(status, REQUEST_ID));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(409);
        response.bodyJson().extractingPath("$.status").isEqualTo(status.name());
        response.bodyJson().extractingPath("$.effectiveRequestId").isEqualTo(REQUEST_ID.toString());
        response.bodyJson().doesNotHavePath("$.detail");
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

    @Test
    void shouldPreserveDownstreamConflictInsteadOfReportingAccepted() {
        when(coordinator.request(any())).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(409);
        response.bodyJson().extractingPath("$.code").isEqualTo("DOWNSTREAM_RPC_ERROR");
    }

    @Test
    void shouldReportUnknownOutcomeWhenDownstreamTimesOut() {
        when(coordinator.request(any()))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException("read timed out")));

        MvcTestResultAssert response = assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody()));

        response.hasStatus(504);
        response.bodyJson().extractingPath("$.code").isEqualTo("DOWNSTREAM_TIMEOUT");
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
