package com.flowzati.archone.architecture.fixture.compliant.sales.application.invocation;

import java.util.List;

public record PlaceSalesOrderCommand(String orderId, List<SalesOrderLine> lines) {}
