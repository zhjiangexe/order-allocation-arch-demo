package com.flowzati.archone.fulfillment.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.enums.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.usecase.GetCancellationProcessUsecase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(CancellationProcessRest.class)
@TestPropertySource(properties = "archone.fulfillment.orchestration-mode=events")
class CancellationProcessRestTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-20T08:00:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private GetCancellationProcessUsecase usecase;

    @Test
    @DisplayName("回應欄位是固定的 API 契約：與改用專用 Response 之前完全相同")
    void returnsTheCancellationProgress() {
        when(usecase.get(ORDER_ID, REQUEST_ID))
                .thenReturn(Optional.of(new CancellationProcess(
                        REQUEST_ID,
                        ORDER_ID,
                        REQUESTED_AT,
                        "Customer changed mind",
                        CancellationProcessState.WAITING_ORDERING,
                        WmsCancellationOutcome.SHIPMENT_CANCELLED,
                        null)));

        MvcTestResultAssert response =
                assertThat(mvc.get().uri("/orders/{orderId}/cancellation-requests/{requestId}", ORDER_ID, REQUEST_ID));

        response.hasStatusOk();
        response.bodyJson().extractingPath("$.requestId").isEqualTo(REQUEST_ID.toString());
        response.bodyJson().extractingPath("$.orderId").isEqualTo(ORDER_ID.toString());
        response.bodyJson().extractingPath("$.requestedAt").isEqualTo("2026-08-20T08:00:00Z");
        response.bodyJson().extractingPath("$.reason").isEqualTo("Customer changed mind");
        response.bodyJson().extractingPath("$.state").isEqualTo("WAITING_ORDERING");
        response.bodyJson().extractingPath("$.wmsOutcome").isEqualTo("SHIPMENT_CANCELLED");
        response.bodyJson()
                .hasPathSatisfying(
                        "$.orderingOutcome", value -> assertThat(value).isNull());
    }

    @Test
    void unknownRequestIsNotFound() {
        when(usecase.get(ORDER_ID, REQUEST_ID)).thenReturn(Optional.empty());

        assertThat(mvc.get().uri("/orders/{orderId}/cancellation-requests/{requestId}", ORDER_ID, REQUEST_ID))
                .hasStatus(404);
    }
}
