package com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase;

public class PlaceSalesOrderUsecase {
    private final com.flowzati.archone.architecture.fixture.compliant.sales.application.store.SalesOrderStore
            salesOrderStore;
    private final com.flowzati.archone.architecture.fixture.compliant.sales.application.port.SalesOrderPlacedPublisher
            publisher;

    public PlaceSalesOrderUsecase(
            com.flowzati.archone.architecture.fixture.compliant.sales.application.store.SalesOrderStore salesOrderStore,
            com.flowzati.archone.architecture.fixture.compliant.sales.application.port.SalesOrderPlacedPublisher
                    publisher) {
        this.salesOrderStore = salesOrderStore;
        this.publisher = publisher;
    }

    public void place(
            com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation.PlaceSalesOrderCommand
                    command) {
        salesOrderStore.save(new com.flowzati.archone.architecture.fixture.compliant.sales.domain.aggregate.SalesOrder(
                command.orderId()));
        publisher.publish(
                new com.flowzati.archone.architecture.fixture.compliant.sales.application.event.SalesOrderPlaced(
                        command.orderId()));
    }
}
