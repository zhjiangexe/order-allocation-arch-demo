package com.flowzati.archone.bootstrap.fulfillment.temporal;

import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.command.CompleteOutboundMovementsCommand;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities;
import com.flowzati.archone.ordering.application.command.RecordOrderFulfillmentCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;

/** Temporal Activity 到既有 Inventory／Ordering application use case 的薄轉接層。 */
public class OrderPromisingActivitiesImpl implements OrderPromisingActivities {

    private final AllocateOrderUsecase allocateOrderUsecase;
    private final CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase;
    private final RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase;
    private final CancelOrderUsecase cancelOrderUsecase;

    public OrderPromisingActivitiesImpl(
            AllocateOrderUsecase allocateOrderUsecase,
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase,
            RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase,
            CancelOrderUsecase cancelOrderUsecase) {
        this.allocateOrderUsecase = allocateOrderUsecase;
        this.completeOutboundMovementsUsecase = completeOutboundMovementsUsecase;
        this.recordOrderFulfillmentUsecase = recordOrderFulfillmentUsecase;
        this.cancelOrderUsecase = cancelOrderUsecase;
    }

    @Override
    public void requestAllocation(RequestAllocation input) {
        allocateOrderUsecase.execute(new AllocateOrderCommand(input.orderId()));
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovements input) {
        completeOutboundMovementsUsecase.execute(new CompleteOutboundMovementsCommand(
                input.allocationId(), input.orderId(), input.shipmentId(), input.movementIds(), input.handedOverAt()));
    }

    @Override
    public void recordOrderFulfillment(RecordOrderFulfillment input) {
        recordOrderFulfillmentUsecase.execute(new RecordOrderFulfillmentCommand(input.orderId(), input.fulfilledAt()));
    }

    @Override
    public CancelOrderResult cancelOrder(CancelOrder input) {
        Order.CancellationResult result = cancelOrderUsecase.cancel(input.orderId(), input.requestedAt());
        CancelOrderStatus status =
                switch (result) {
                    case CANCELLED -> CancelOrderStatus.CANCELLED;
                    case ALREADY_CANCELLED -> CancelOrderStatus.ALREADY_CANCELLED;
                    case REJECTED -> CancelOrderStatus.REJECTED;
                };
        return new CancelOrderResult(input.orderId(), status);
    }
}
