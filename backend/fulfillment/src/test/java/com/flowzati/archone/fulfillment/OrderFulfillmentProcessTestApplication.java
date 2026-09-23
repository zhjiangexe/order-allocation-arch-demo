package com.flowzati.archone.fulfillment;

import com.flowzati.archone.fulfillment.entrypoint.rest.EventDrivenOrderCancellationRest;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 只供 Order Fulfillment Process 的 Spring slice tests 尋找，不啟動 deployment runtime。 */
@SpringBootApplication(scanBasePackageClasses = EventDrivenOrderCancellationRest.class)
public class OrderFulfillmentProcessTestApplication {}
