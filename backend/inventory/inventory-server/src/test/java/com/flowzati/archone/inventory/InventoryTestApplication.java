package com.flowzati.archone.inventory;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 只供 Inventory 的 Spring slice tests 尋找，不啟動 bootstrap runtime。 */
@SpringBootApplication(scanBasePackages = "com.flowzati.archone.inventory")
public class InventoryTestApplication {}
