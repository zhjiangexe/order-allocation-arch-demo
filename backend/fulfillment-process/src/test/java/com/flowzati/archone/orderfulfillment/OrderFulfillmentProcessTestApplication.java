package com.flowzati.archone.orderfulfillment;

import com.flowzati.archone.orderfulfillment.entrypoint.rest.OrderCancellationRest;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 只供 Order Fulfillment Process 的 Spring slice tests 尋找，不啟動 deployment runtime。 */
@SpringBootApplication(scanBasePackageClasses = OrderCancellationRest.class)
public class OrderFulfillmentProcessTestApplication {}
