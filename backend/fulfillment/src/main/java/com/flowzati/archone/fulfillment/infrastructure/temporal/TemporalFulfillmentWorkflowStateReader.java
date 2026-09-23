package com.flowzati.archone.fulfillment.infrastructure.temporal;

import com.flowzati.archone.foundation.configuration.FulfillmentOrchestrationMode;
import com.flowzati.archone.fulfillment.application.port.FulfillmentWorkflowStateReader;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import io.grpc.Status;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowServiceException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reads Workflow state using the shared Temporal client and its existing timeout/retry settings. */
@Component
@ConditionalOnProperty(
        name = FulfillmentOrchestrationMode.ORCHESTRATION_MODE,
        havingValue = FulfillmentOrchestrationMode.TEMPORAL)
public class TemporalFulfillmentWorkflowStateReader implements FulfillmentWorkflowStateReader {
    private static final Logger log = LoggerFactory.getLogger(TemporalFulfillmentWorkflowStateReader.class);
    private final WorkflowClient workflowClient;

    public TemporalFulfillmentWorkflowStateReader(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public FulfillmentWorkflowQueryResult find(UUID orderId) {
        String workflowId = OrderFulfillmentWorkflow.workflowId(orderId);
        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.class, workflowId);
        try {
            return new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.AVAILABLE, workflow.state());
        } catch (WorkflowNotFoundException exception) {
            return new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.NOT_FOUND, null);
        } catch (WorkflowServiceException exception) {
            Status.Code code = Status.fromThrowable(exception).getCode();
            if (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) {
                throw exception;
            }
            log.warn("Workflow state query unavailable for {}", workflowId, exception);
            return new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.UNAVAILABLE, null);
        }
    }
}
