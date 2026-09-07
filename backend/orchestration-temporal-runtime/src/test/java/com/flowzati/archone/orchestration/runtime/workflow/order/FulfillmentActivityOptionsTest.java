package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.RequestAllocationActivityInput;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FulfillmentActivityOptionsTest {

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(
                        new ApplicationConflictException(() -> "SOURCE_CONFLICT", "Source snapshot differs"), false),
                Arguments.of(new DomainConflictException(() -> "REQUEST_CONFLICT", "Request content differs"), false),
                Arguments.of(new IllegalArgumentException("Invalid completion proof"), false),
                Arguments.of(new IllegalStateException("Projection has not caught up"), true),
                // The server matches failure types exactly, not Java exception inheritance.
                Arguments.of(new NumberFormatException("Subclass requires its own policy"), true));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void appliesTheSharedRetryPolicyToActualActivityExecution(RuntimeException failure, boolean retry) {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var attempts = new AtomicInteger();
            var activity =
                    mock(InventoryAllocationActivities.class, withSettings().withoutAnnotations());
            doAnswer(invocation -> {
                        if (attempts.incrementAndGet() == 1) {
                            throw failure;
                        }
                        return null;
                    })
                    .when(activity)
                    .requestAllocation(any());
            environment
                    .newWorker("retry-policy-test")
                    .registerWorkflowImplementationTypes(RetryPolicyWorkflowImpl.class);
            environment.newWorker(InventoryAllocationActivities.TASK_QUEUE).registerActivitiesImplementations(activity);
            environment.start();
            var workflow = environment
                    .getWorkflowClient()
                    .newWorkflowStub(
                            RetryPolicyWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setTaskQueue("retry-policy-test")
                                    .build());
            var input = new RequestAllocationActivityInput("process-1", UUID.randomUUID(), Instant.now());

            if (retry) {
                workflow.execute(input);
                assertThat(attempts.get()).isEqualTo(2);
            } else {
                assertThatThrownBy(() -> workflow.execute(input))
                        .hasRootCauseInstanceOf(ApplicationFailure.class)
                        .hasStackTraceContaining(failure.getClass().getName());
                assertThat(attempts.get()).isEqualTo(1);
            }
        }
    }

    @WorkflowInterface
    public interface RetryPolicyWorkflow {
        @WorkflowMethod
        void execute(RequestAllocationActivityInput input);
    }

    public static class RetryPolicyWorkflowImpl implements RetryPolicyWorkflow {
        @Override
        public void execute(RequestAllocationActivityInput input) {
            Workflow.newActivityStub(
                            InventoryAllocationActivities.class,
                            FulfillmentActivityOptions.forTaskQueue(InventoryAllocationActivities.TASK_QUEUE))
                    .requestAllocation(input);
        }
    }
}
