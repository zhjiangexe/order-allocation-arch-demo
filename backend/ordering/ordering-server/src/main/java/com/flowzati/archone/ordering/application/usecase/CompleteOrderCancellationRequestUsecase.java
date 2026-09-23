package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.ordering.application.event.OrderCancellationResolved;
import com.flowzati.archone.ordering.application.invocation.CancelOrderCommand;
import com.flowzati.archone.ordering.application.port.OrderCancellationResolvedPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.EVENTS,
        matchIfMissing = true)
public class CompleteOrderCancellationRequestUsecase {
    private final CancelOrderUsecase cancelOrder;
    private final OrderCancellationResolvedPublisher publisher;

    public CompleteOrderCancellationRequestUsecase(
            CancelOrderUsecase cancelOrder, OrderCancellationResolvedPublisher publisher) {
        this.cancelOrder = cancelOrder;
        this.publisher = publisher;
    }

    @Transactional
    public void complete(CancelOrderCommand command) {
        var status = cancelOrder.cancel(command);
        publisher.publish(new OrderCancellationResolved(command.requestId(), command.orderId(), status));
    }
}
