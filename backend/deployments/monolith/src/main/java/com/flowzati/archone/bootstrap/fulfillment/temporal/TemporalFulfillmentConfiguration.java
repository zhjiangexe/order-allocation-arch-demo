package com.flowzati.archone.bootstrap.fulfillment.temporal;

import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.balance.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow;
import com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflowImpl;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shared.application.IdGenerator;
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
    OrderPromisingActivitiesImpl orderPromisingActivities(
            AllocateOrderUsecase allocateOrderUsecase,
            CompleteOutboundMovementsUsecase completeOutboundMovementsUsecase,
            RecordOrderFulfillmentUsecase recordOrderFulfillmentUsecase,
            CancelOrderUsecase cancelOrderUsecase) {
        return new OrderPromisingActivitiesImpl(
                allocateOrderUsecase,
                completeOutboundMovementsUsecase,
                recordOrderFulfillmentUsecase,
                cancelOrderUsecase);
    }

    @Bean
    WmsActivitiesImpl wmsActivities(
            CreateShipmentUsecase createShipmentUsecase,
            CancelShipmentUsecase cancelShipmentUsecase,
            IdGenerator idGenerator) {
        return new WmsActivitiesImpl(createShipmentUsecase, cancelShipmentUsecase, idGenerator);
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(
            WorkflowClient workflowClient,
            OrderPromisingActivitiesImpl orderPromisingActivities,
            WmsActivitiesImpl wmsActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        Worker workflowWorker = factory.newWorker(OrderFulfillmentProcessWorkflow.TASK_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(OrderFulfillmentProcessWorkflowImpl.class);

        Worker orderPromisingWorker = factory.newWorker(OrderPromisingActivities.TASK_QUEUE);
        orderPromisingWorker.registerActivitiesImplementations(orderPromisingActivities);

        Worker wmsWorker = factory.newWorker(WmsActivities.TASK_QUEUE);
        wmsWorker.registerActivitiesImplementations(wmsActivities);

        factory.start();
        return factory;
    }
}
