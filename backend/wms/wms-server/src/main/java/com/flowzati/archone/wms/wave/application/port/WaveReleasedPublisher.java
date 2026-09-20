package com.flowzati.archone.wms.wave.application.port;

import com.flowzati.archone.wms.wave.application.event.WaveReleased;

@FunctionalInterface
public interface WaveReleasedPublisher {

    void publish(WaveReleased event);
}
