package com.flowzati.archone.bootstrap.configuration;

import com.flowzati.archone.orderfulfillment.configuration.OrderFulfillmentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers deployable-level fulfillment process settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderFulfillmentProperties.class)
public class OrderFulfillmentConfiguration {}
