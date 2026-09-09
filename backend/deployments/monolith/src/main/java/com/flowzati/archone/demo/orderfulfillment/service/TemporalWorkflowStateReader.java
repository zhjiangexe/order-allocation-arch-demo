package com.flowzati.archone.demo.orderfulfillment.service;

import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryResult;
import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryStatus;
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
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalWorkflowStateReader {
    private static final Logger log = LoggerFactory.getLogger(TemporalWorkflowStateReader.class);
    private final WorkflowClient workflowClient;

    public TemporalWorkflowStateReader(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public WorkflowQueryResult find(UUID orderId) {
        String workflowId = OrderFulfillmentWorkflow.workflowId(orderId);
        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.class, workflowId);
        try {
            return new WorkflowQueryResult(WorkflowQueryStatus.AVAILABLE, workflow.state());
        } catch (WorkflowNotFoundException exception) {
            return new WorkflowQueryResult(WorkflowQueryStatus.NOT_FOUND, null);
        } catch (WorkflowServiceException exception) {
            Status.Code code = Status.fromThrowable(exception).getCode();
            if (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) {
                throw exception;
            }
            log.warn("Workflow state query unavailable for {}", workflowId, exception);
            return new WorkflowQueryResult(WorkflowQueryStatus.UNAVAILABLE, null);
        }
    }
}
