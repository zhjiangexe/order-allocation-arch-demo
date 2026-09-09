package com.flowzati.archone.foundation.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class SimulationUtilTest {

    private static final String PROPERTY = "archone.simulation.sleep-enabled";
    private String previousSetting;

    @BeforeEach
    void rememberSetting() {
        previousSetting = System.getProperty(PROPERTY);
    }

    @AfterEach
    void restoreSetting() {
        if (previousSetting == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previousSetting);
        }
    }

    @Test
    void testWorkerDisablesSimulationDelay() {
        assertThat(System.getProperty(PROPERTY)).isEqualTo("false");
        assertTimeout(Duration.ofSeconds(1), () -> SimulationUtil.sleep(3_000));
    }

    @Test
    void waitsForTheRequestedDuration() {
        System.clearProperty(PROPERTY); // 正常啟動預設啟用；此測試僅等待 10ms。
        long started = System.nanoTime();
        SimulationUtil.sleep(10);
        assertThat(System.nanoTime() - started)
                .isGreaterThanOrEqualTo(Duration.ofMillis(10).toNanos());
    }

    @Test
    void nonPositiveDurationsAreNoOps() {
        assertThatNoException().isThrownBy(() -> SimulationUtil.sleep(0));
        assertThatNoException().isThrownBy(() -> SimulationUtil.sleep(-1));
    }

    @Test
    void interruptionDoesNotEscapeAndTheInterruptFlagIsPreserved() {
        System.setProperty(PROPERTY, "true");
        Thread.currentThread().interrupt();
        try {
            assertThatNoException().isThrownBy(() -> SimulationUtil.sleep(3_000));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
