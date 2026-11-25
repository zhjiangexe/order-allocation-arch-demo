package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;

@Entity
public class SalesOrderEntity {

    @EmbeddedId
    private SalesOrderPrimaryKey id;
}
