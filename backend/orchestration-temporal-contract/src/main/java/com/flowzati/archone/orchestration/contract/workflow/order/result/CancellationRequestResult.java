package com.flowzati.archone.orchestration.contract.workflow.order.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.UUID;

/** Update 的立即受理結果，不代表 WMS 或 Order cancellation 已完成，也不是 OrderCancelled fact。 */
@JsonIgnoreProperties("detail") // 相容既有 Temporal payload；新結果不再攜帶說明文字。
public record CancellationRequestResult(CancellationRequestStatus status, UUID effectiveRequestId) {

    public CancellationRequestResult {
        Objects.requireNonNull(status, "Cancellation request status is required");
        Objects.requireNonNull(effectiveRequestId, "Effective cancellation request ID is required");
    }
}
