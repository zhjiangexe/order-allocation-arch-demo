package com.flowzati.archone.fulfillment.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import com.flowzati.archone.fulfillment.application.usecase.RequestWorkflowCancellationUsecase;
import com.flowzati.archone.support.spring.web.validation.GlobalRestExceptionHandler;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

@WebMvcTest(TemporalOrderCancellationRest.class)
@Import(GlobalRestExceptionHandler.class)
@TestPropertySource(
        properties = {
            "archone.fulfillment.orchestration-mode=temporal",
            "archone.web.validation.message-basenames="
                    + "classpath:i18n/validation/constraints_template,"
                    + "classpath:i18n/validation/problem_detail,"
                    + "classpath:i18n/fulfillment/request_field"
        })
class TemporalOrderCancellationRestTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private RequestWorkflowCancellationUsecase usecase;

    @Test
    void acceptsAWorkflowCancellationRequest() {
        when(usecase.request(any()))
                .thenReturn(new FulfillmentCancellationResult(FulfillmentCancellationStatus.ACCEPTED, REQUEST_ID));

        request().hasStatus(202);
    }

    @Test
    void hidesDownstreamResponseDetails() {
        when(usecase.request(any())).thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        MvcTestResultAssert response = request();

        response.hasStatus(502);
        response.bodyJson().extractingPath("$.code").isEqualTo("DOWNSTREAM_SERVICE_ERROR");
        response.bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The system could not complete the request at this time");
        response.bodyJson().doesNotHavePath("$.downstreamStatus");
    }

    @Test
    void reportsUnknownOutcomeWhenTheWorkflowCallTimesOut() {
        when(usecase.request(any()))
                .thenThrow(new ResourceAccessException("timeout", new SocketTimeoutException("read timed out")));

        MvcTestResultAssert response = request();

        response.hasStatus(504);
        response.bodyJson().extractingPath("$.code").isEqualTo("DOWNSTREAM_SERVICE_TIMEOUT");
    }

    private MvcTestResultAssert request() {
        return assertThat(mvc.post()
                .uri("/orders/{orderId}/cancellation-requests", ORDER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {
                    "requestId": "%s",
                    "requestedAt": "%s",
                    "reason": "Customer changed mind"
                  }
                  """.formatted(REQUEST_ID, REQUESTED_AT)));
    }
}
