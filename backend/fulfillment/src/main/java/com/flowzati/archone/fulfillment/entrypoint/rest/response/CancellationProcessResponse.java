package com.flowzati.archone.fulfillment.entrypoint.rest.response;

import com.flowzati.archone.fulfillment.application.enums.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.enums.OrderingCancellationOutcome;
import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import java.time.Instant;
import java.util.UUID;

/** 一筆取消請求目前的進度。欄位是對外 API 契約，與內部的 {@link CancellationProcess} 分開演進。 */
public record CancellationProcessResponse(
        UUID requestId,
        UUID orderId,
        Instant requestedAt,
        String reason,
        CancellationProcessState state,
        WmsCancellationOutcome wmsOutcome,
        OrderingCancellationOutcome orderingOutcome) {

    public static CancellationProcessResponse from(CancellationProcess process) {
        return new CancellationProcessResponse(
                process.requestId(),
                process.orderId(),
                process.requestedAt(),
                process.reason(),
                process.state(),
                process.wmsOutcome(),
                process.orderingOutcome());
    }
}
