package com.flowzati.archone.architecture.fixture.violation.entityoutsidemodel.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ParcelEntity {
    @Id
    private String id;
}
