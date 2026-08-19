package com.flowzati.archone.wms.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** WMS deployable；pure domain/application code remains in the {@code wms} module. */
@SpringBootApplication
public class WmsRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WmsRuntimeApplication.class, args);
    }
}
