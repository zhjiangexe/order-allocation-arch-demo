package com.flowzati.archone.ordering.entrypoint.temporal;

import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.OrderingActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.invocation.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import io.temporal.failure.ApplicationFailure;

/** Temporal Activity contract 到 Ordering application use cases 的 inbound adapter。 */
public final class TemporalOrderingActivitiesAdapter implements OrderingActivities {

    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase;
    private final CancelOrderUsecase cancelOrderUsecase;

    public TemporalOrderingActivitiesAdapter(
            RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase, CancelOrderUsecase cancelOrderUsecase) {
        this.recordOrderFulfillmentUsecase = recordOrderFulfillmentUsecase;
        this.cancelOrderUsecase = cancelOrderUsecase;
    }

    @Override
    public void recordOrderFulfillment(RecordOrderFulfillmentActivityInput input) {
        try {
            recordOrderFulfillmentUsecase.execute(
                    new RecordOrderFulfillmentCommand(input.orderId(), input.shipmentId(), input.fulfilledAt()));
        } catch (com.flowzati.archone.foundation.error.DomainConflictException exception) {
            throw nonRetryable(exception, "ORDER_FULFILLMENT_CONFLICT");
        }
    }

    @Override
    public CancelOrderActivityResult cancelOrder(CancelOrderActivityInput input) {
        Order.CancellationStatus result;
        try {
            result = cancelOrderUsecase.cancel(
                    new CancelOrderCommand(input.requestId(), input.orderId(), input.cancelledAt(), input.reason()));
        } catch (com.flowzati.archone.foundation.error.DomainConflictException exception) {
            throw nonRetryable(exception, "ORDER_CANCELLATION_REQUEST_CONFLICT");
        }
        CancelOrderActivityStatus status =
                switch (result) {
                    case CANCELLED -> CancelOrderActivityStatus.CANCELLED;
                    case ALREADY_CANCELLED -> CancelOrderActivityStatus.ALREADY_CANCELLED;
                    case REJECTED -> CancelOrderActivityStatus.REJECTED;
                };
        return new CancelOrderActivityResult(input.orderId(), status);
    }

    private static ApplicationFailure nonRetryable(RuntimeException exception, String type) {
        return ApplicationFailure.newNonRetryableFailure(exception.getMessage(), type);
    }
}
