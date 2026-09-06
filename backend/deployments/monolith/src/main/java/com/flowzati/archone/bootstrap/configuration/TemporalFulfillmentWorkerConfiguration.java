package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import com.flowzati.archone.orchestration.contract.activity.ordering.OrderActivities;
import com.flowzati.archone.orchestration.contract.activity.wms.ShipmentActivities;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.runtime.workflow.order.OrderFulfillmentWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 組裝此 deployable 承載的 Fulfillment Workflow 與 Activity Workers。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalFulfillmentWorkerConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(
            WorkflowClient workflowClient,
            InventoryAllocationActivities inventoryAllocationActivities,
            InventoryMovementActivities inventoryMovementActivities,
            OrderActivities orderActivities,
            ShipmentActivities shipmentActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        Worker workflowWorker = factory.newWorker(OrderFulfillmentWorkflow.TASK_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);

        Worker orderPromisingWorker = factory.newWorker(InventoryAllocationActivities.TASK_QUEUE);
        orderPromisingWorker.registerActivitiesImplementations(
                inventoryAllocationActivities, inventoryMovementActivities, orderActivities);

        Worker wmsWorker = factory.newWorker(ShipmentActivities.TASK_QUEUE);
        wmsWorker.registerActivitiesImplementations(shipmentActivities);

        factory.start();
        return factory;
    }
}
