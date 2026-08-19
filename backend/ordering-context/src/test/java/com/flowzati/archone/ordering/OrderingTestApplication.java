package com.flowzati.archone.ordering;

import com.flowzati.archone.ordering.entrypoint.rest.OrderController;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 只供 Ordering 的 Spring slice tests 尋找，不啟動 bootstrap runtime。 */
@SpringBootApplication(scanBasePackageClasses = OrderController.class)
public class OrderingTestApplication {}
