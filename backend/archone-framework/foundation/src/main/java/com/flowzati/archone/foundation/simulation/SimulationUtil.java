package com.flowzati.archone.foundation.simulation;

/** 本機演示用的阻塞延遲；不可從 Temporal Workflow 呼叫。 */
public final class SimulationUtil {

    private SimulationUtil() {}

    /** 等待指定毫秒數；非正數直接返回，中斷時提前返回並保留中斷旗標，不向外拋出例外。 */
    public static void sleep(long millis) {
        if (millis <= 0 || !Boolean.parseBoolean(System.getProperty("archone.simulation.sleep-enabled", "true"))) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
