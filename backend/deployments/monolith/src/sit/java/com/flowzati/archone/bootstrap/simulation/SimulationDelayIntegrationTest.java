package com.flowzati.archone.bootstrap.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.flowzati.archone.foundation.simulation.SimulationUtil;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 確認 SIT 的獨立測試 JVM 也套用共用的演示延遲開關。 */
class SimulationDelayIntegrationTest {

    @Test
    void sitWorkerSkipsTheThreeSecondActivityDelay() {
        assertThat(System.getProperty("archone.simulation.sleep-enabled")).isEqualTo("false");
        assertTimeout(Duration.ofSeconds(1), () -> SimulationUtil.sleep(3_000));
    }
}
