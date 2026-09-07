package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.DomainConflictException;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/**
 * 履約 Activity 的共用技術重試設定。Start-to-close timeout 限制單次執行，不限制整體重試時間；
 * 未設定 maximum attempts 或 schedule-to-close timeout，可重試失敗會持續重試，直到成功或 execution 結束。
 */
public final class FulfillmentActivityOptions {

    private static final RetryOptions RETRY_OPTIONS = RetryOptions.newBuilder()
            // SDK failure types use exact class names; subclasses are not matched automatically.
            .setDoNotRetry(
                    IllegalArgumentException.class.getName(),
                    ApplicationConflictException.class.getName(),
                    DomainConflictException.class.getName())
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .build();

    private FulfillmentActivityOptions() {}

    public static ActivityOptions forTaskQueue(String taskQueue) {
        return ActivityOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RETRY_OPTIONS)
                .build();
    }
}
