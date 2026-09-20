package com.flowzati.archone.ordering.api.cancellation;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Internal Ordering RPC contract shared by local and remote callers. */
public interface OrderCancellationApi {

    @PostExchange("/internal/ordering/cancellation/assess")
    OrderCancellationAssessment assess(@RequestBody OrderCancellationRequest request);
}
