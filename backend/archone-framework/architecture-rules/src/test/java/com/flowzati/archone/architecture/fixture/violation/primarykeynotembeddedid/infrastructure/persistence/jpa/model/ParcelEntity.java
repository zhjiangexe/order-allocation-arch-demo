package com.flowzati.archone.architecture.fixture.violation.primarykeynotembeddedid.infrastructure.persistence.jpa.model;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class ParcelEntity {
    @Id
    private String id;

    @Embedded
    private ParcelPrimaryKey key;
}
