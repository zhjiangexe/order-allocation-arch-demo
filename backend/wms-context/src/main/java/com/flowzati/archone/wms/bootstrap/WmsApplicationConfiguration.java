package com.flowzati.archone.wms.bootstrap;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** WMS module composition root. */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.flowzati.archone.wms")
public class WmsApplicationConfiguration {}
