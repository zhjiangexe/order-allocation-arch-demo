package com.flowzati.archone.ordering.entrypoint.temporal;

import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.ordering.OrderActivities;
import com.flowzati.archone.orchestration.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal Order Activity contract 到 Ordering application use cases 的 inbound adapter。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public final class TemporalOrderActivitiesAdapter implements OrderActivities {

    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase;
    private final CancelOrderUsecase cancelOrderUsecase;

    public TemporalOrderActivitiesAdapter(
            RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase, CancelOrderUsecase cancelOrderUsecase) {
        this.recordOrderFulfillmentUsecase = recordOrderFulfillmentUsecase;
        this.cancelOrderUsecase = cancelOrderUsecase;
    }

    @Override
    public void recordOrderFulfillment(RecordOrderFulfillmentActivityInput input) {
        recordOrderFulfillmentUsecase.execute(
                new RecordOrderFulfillmentCommand(input.orderId(), input.shipmentId(), input.fulfilledAt()));
    }

    @Override
    public CancelOrderActivityResult cancelOrder(CancelOrderActivityInput input) {
        Order.CancellationStatus result = cancelOrderUsecase.cancel(
                new CancelOrderCommand(input.requestId(), input.orderId(), input.cancelledAt(), input.reason()));
        CancelOrderActivityStatus status =
                switch (result) {
                    case CANCELLED -> CancelOrderActivityStatus.CANCELLED;
                    case ALREADY_CANCELLED -> CancelOrderActivityStatus.ALREADY_CANCELLED;
                    case REJECTED -> CancelOrderActivityStatus.REJECTED;
                };
        return new CancelOrderActivityResult(input.orderId(), status);
    }
}
