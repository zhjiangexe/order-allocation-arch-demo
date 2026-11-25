package com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.model.SalesOrderEntity;
import com.flowzati.archone.architecture.fixture.compliant.sales.infrastructure.persistence.jpa.model.SalesOrderPrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaSalesOrderRepository extends JpaRepository<SalesOrderEntity, SalesOrderPrimaryKey> {}
