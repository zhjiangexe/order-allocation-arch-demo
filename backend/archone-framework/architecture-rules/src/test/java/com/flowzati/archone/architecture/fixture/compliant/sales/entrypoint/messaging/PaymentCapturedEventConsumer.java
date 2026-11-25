package com.flowzati.archone.architecture.fixture.compliant.sales.entrypoint.messaging;

public class PaymentCapturedEventConsumer {
    private final com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase.PlaceSalesOrderUsecase
            usecase;

    public PaymentCapturedEventConsumer(
            com.flowzati.archone.architecture.fixture.compliant.sales.application.usecase.PlaceSalesOrderUsecase
                    usecase) {
        this.usecase = usecase;
    }

    public void onMessage(String orderId) {
        usecase.place(
                new com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation
                        .PlaceSalesOrderCommand(orderId, java.util.List.of()));
    }
}
