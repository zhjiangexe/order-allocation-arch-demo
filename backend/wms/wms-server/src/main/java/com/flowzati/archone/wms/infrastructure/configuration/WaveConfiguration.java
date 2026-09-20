package com.flowzati.archone.wms.infrastructure.configuration;

import com.flowzati.archone.wms.wave.domain.service.WavePlanner;
import com.flowzati.archone.wms.wave.domain.service.impl.PriorityCapacityWavePlanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the Wave planning policy used by this WMS deployment. */
@Configuration(proxyBeanMethods = false)
public class WaveConfiguration {

    @Bean
    WavePlanner wavePlanner() {
        return new PriorityCapacityWavePlanner();
    }
}
