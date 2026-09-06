package com.flowzati.archone.bootstrap.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers deployable-level fulfillment process settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderFulfillmentProperties.class)
public class OrderFulfillmentConfiguration {}
