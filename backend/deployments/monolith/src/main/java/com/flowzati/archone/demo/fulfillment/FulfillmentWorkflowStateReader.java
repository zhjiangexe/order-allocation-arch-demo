package com.flowzati.archone.demo.fulfillment;

import com.flowzati.archone.bootstrap.fulfillment.FulfillmentOrchestrationMode;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Temporal mode 才查 Workflow Query；events mode 不假造不存在的 workflow state。 */
@Component
public class FulfillmentWorkflowStateReader {

    private final FulfillmentOrchestrationMode mode;
    private final ObjectProvider<WorkflowClient> workflowClient;

    public FulfillmentWorkflowStateReader(
            FulfillmentOrchestrationMode mode, ObjectProvider<WorkflowClient> workflowClient) {
        this.mode = mode;
        this.workflowClient = workflowClient;
    }

    public Optional<OrderFulfillmentWorkflowState> find(UUID orderId) {
        if (mode != FulfillmentOrchestrationMode.TEMPORAL) {
            return Optional.empty();
        }
        WorkflowClient client = workflowClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("Temporal mode requires a WorkflowClient");
        }
        OrderFulfillmentWorkflow workflow =
                client.newWorkflowStub(OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
        try {
            return Optional.of(workflow.state());
        } catch (WorkflowNotFoundException exception) {
            return Optional.empty();
        }
    }
}
