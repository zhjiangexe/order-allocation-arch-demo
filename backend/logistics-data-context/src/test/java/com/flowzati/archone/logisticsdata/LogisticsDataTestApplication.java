package com.flowzati.archone.logisticsdata;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 只供 Logistics Data 的 Spring slice tests 尋找，不啟動 bootstrap runtime。 */
@SpringBootApplication(scanBasePackages = "com.flowzati.archone.logisticsdata")
public class LogisticsDataTestApplication {}
