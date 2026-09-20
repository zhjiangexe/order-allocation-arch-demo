package com.flowzati.archone.bootstrap.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.entrypoint.rest.OrderCancellationRpcRest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalCancellationRpcWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GetOrderUsecase.class, () -> mock(GetOrderUsecase.class))
            .withUserConfiguration(OrderCancellationRpcRest.class);

    @Test
    void monolithInjectsLocalImplementationsOnly() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OrderCancellationApi.class);
            assertThat(context.getBean(OrderCancellationApi.class)).isInstanceOf(OrderCancellationRpcRest.class);
        });
    }
}
