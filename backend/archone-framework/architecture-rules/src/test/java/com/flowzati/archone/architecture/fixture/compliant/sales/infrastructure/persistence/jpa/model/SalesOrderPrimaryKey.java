package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.model;

import jakarta.persistence.Embeddable;
import java.io.Serializable;

@Embeddable
public class SalesOrderPrimaryKey implements Serializable {

    private String channel;
    private String orderNumber;
}
