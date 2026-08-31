package com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.receiving.domain.aggregate.InboundOperation;
import com.flowzati.archone.wms.receiving.domain.type.InboundStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "wms_inbound_operations")
public class InboundOperationEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "external_reference", nullable = false, unique = true)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboundStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "inbound_operation_id", nullable = false)
    @OrderBy("skuCode ASC, id ASC")
    private List<InboundLineEntity> expectedLines = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    protected InboundOperationEntity() {}

    public InboundOperationEntity(InboundOperation operation) {
        this.id = operation.id();
        replaceFrom(operation);
    }

    public void replaceFrom(InboundOperation operation) {
        if (id != null && !id.equals(operation.id())) {
            throw new IllegalArgumentException("Cannot replace a different InboundOperation entity");
        }
        this.id = operation.id();
        this.ownerId = operation.ownerId();
        this.facilityId = operation.facilityId();
        this.externalReference = operation.externalReference();
        this.status = operation.status();
        this.expectedLines.clear();
        operation.expectedLines().stream().map(InboundLineEntity::new).forEach(this.expectedLines::add);
    }

    public InboundOperation toDomain() {
        return InboundOperation.rehydrate(
                id,
                ownerId,
                facilityId,
                externalReference,
                expectedLines.stream().map(InboundLineEntity::toDomain).toList(),
                status);
    }
}
