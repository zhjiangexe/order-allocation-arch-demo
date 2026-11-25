package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.mapper;

import com.flowzati.archone.architecture.fixture.compliant.sales.domain.aggregate.SalesOrder;
import com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.model.SalesOrderEntity;

public class SalesOrderMapper {
    public SalesOrder toDomain(SalesOrderEntity entity) {
        return new SalesOrder("id");
    }
}
