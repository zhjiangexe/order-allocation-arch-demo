package com.flowzati.archone.bootstrap.fulfillment.temporal;

import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.entrypoint.temporal.TemporalInventoryActivitiesAdapter;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentWorkflowImpl;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.entrypoint.temporal.TemporalOrderingActivitiesAdapter;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.entrypoint.temporal.TemporalWmsActivitiesAdapter;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Monolith 的 Temporal runtime 組裝；bounded contexts 本身不建立 client 或 worker。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalFulfillmentConfiguration {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalWorkflowServiceStubs(
            @Value("${archone.temporal.target:localhost:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient temporalWorkflowClient(
            WorkflowServiceStubs serviceStubs, @Value("${archone.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean
    TemporalInventoryActivitiesAdapter inventoryActivities(
            AllocateOrderUsecase allocateOrderUsecase,
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase) {
        return new TemporalInventoryActivitiesAdapter(allocateOrderUsecase, completeOutboundMovementsUsecase);
    }

    @Bean
    TemporalOrderingActivitiesAdapter orderingActivities(
            RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase, CancelOrderUsecase cancelOrderUsecase) {
        return new TemporalOrderingActivitiesAdapter(recordOrderFulfillmentUsecase, cancelOrderUsecase);
    }

    @Bean
    TemporalWmsActivitiesAdapter wmsActivities(
            CreateShipmentUsecase createShipmentUsecase, CancelShipmentUsecase cancelShipmentUsecase) {
        return new TemporalWmsActivitiesAdapter(createShipmentUsecase, cancelShipmentUsecase);
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(
            WorkflowClient workflowClient,
            TemporalInventoryActivitiesAdapter inventoryActivities,
            TemporalOrderingActivitiesAdapter orderingActivities,
            TemporalWmsActivitiesAdapter wmsActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        Worker workflowWorker = factory.newWorker(OrderFulfillmentWorkflow.TASK_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);

        Worker orderPromisingWorker = factory.newWorker(InventoryActivities.TASK_QUEUE);
        orderPromisingWorker.registerActivitiesImplementations(inventoryActivities, orderingActivities);

        Worker wmsWorker = factory.newWorker(WmsActivities.TASK_QUEUE);
        wmsWorker.registerActivitiesImplementations(wmsActivities);

        factory.start();
        return factory;
    }
}
