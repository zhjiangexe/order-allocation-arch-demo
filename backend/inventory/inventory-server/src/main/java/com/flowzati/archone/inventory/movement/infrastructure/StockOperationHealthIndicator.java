package com.flowzati.archone.inventory.movement.infrastructure;

import com.flowzati.archone.inventory.movement.application.store.StockOperationReconciliationStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports canonical movement drift without mutating or auto-repairing inventory state. */
@Component("stockOperation")
public class StockOperationHealthIndicator implements HealthIndicator {

    private static final int SAMPLE_LIMIT = 20;

    private final StockOperationReconciliationStore stockOperationReconciliationStore;
    private final AtomicInteger anomalyPresent = new AtomicInteger();

    public StockOperationHealthIndicator(
            StockOperationReconciliationStore stockOperationReconciliationStore, MeterRegistry meters) {
        this.stockOperationReconciliationStore = stockOperationReconciliationStore;
        meters.gauge("inventory_stock_operation_anomaly_present", anomalyPresent);
    }

    @Override
    public Health health() {
        var report = stockOperationReconciliationStore.inspect(SAMPLE_LIMIT);
        anomalyPresent.set(report.healthy() ? 0 : 1);
        if (report.healthy()) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("heterogeneousOperationIds", report.heterogeneousOperationIds())
                .withDetail("moveLineCoverageMismatchMoveIds", report.moveLineCoverageMismatchMoveIds())
                .withDetail("reservedCounterMismatchStockQuantIds", report.reservedCounterMismatchStockQuantIds())
                .withDetail("sampleLimitPerCategory", SAMPLE_LIMIT)
                .withDetail("repairPolicy", "manual-only")
                .build();
    }
}
