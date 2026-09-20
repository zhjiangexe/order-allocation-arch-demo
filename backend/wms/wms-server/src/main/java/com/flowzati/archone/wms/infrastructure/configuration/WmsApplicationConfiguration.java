package com.flowzati.archone.wms.infrastructure.configuration;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** WMS module composition root. */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.flowzati.archone.wms")
public class WmsApplicationConfiguration {}
