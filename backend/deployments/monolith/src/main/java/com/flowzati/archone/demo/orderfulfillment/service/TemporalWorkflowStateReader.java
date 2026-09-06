package com.flowzati.archone.demo.orderfulfillment.service;

import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** DEMO 專用的 Temporal Workflow Query reader。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalWorkflowStateReader {

    private final WorkflowClient workflowClient;

    public TemporalWorkflowStateReader(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public Optional<OrderFulfillmentSnapshot> find(UUID orderId) {
        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
        try {
            return Optional.of(workflow.state());
        } catch (WorkflowNotFoundException exception) {
            return Optional.empty();
        }
    }
}
