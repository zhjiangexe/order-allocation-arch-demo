package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.mapper.ShipmentMapper;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsShipmentEntity;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.repository.JpaWmsShipmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL/JPA adapter; aggregate mapping completes inside the caller's transaction. */
@Repository
public class JpaShipmentStoreAdapter implements ShipmentStore {

    private final JpaWmsShipmentRepository repository;

    public JpaShipmentStoreAdapter(JpaWmsShipmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findById(UUID shipmentId) {
        return repository.findById(shipmentId).map(ShipmentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByStockOperationId(UUID stockOperationId) {
        return repository.findByStockOperationId(stockOperationId).map(ShipmentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shipment> findByOrderId(UUID orderId) {
        return repository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(ShipmentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByPickTaskId(UUID pickTaskId) {
        return repository.findByPickTaskId(pickTaskId).map(ShipmentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shipment> findWaveCandidates(UUID facilityId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Wave candidate limit must be positive");
        }
        Sort sort = Sort.by(
                Sort.Order.desc("releasePriority"),
                Sort.Order.asc("dispatchBy"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
        return repository
                .findByFacilityIdAndStatus(facilityId, ShipmentStatus.CREATED, PageRequest.of(0, limit, sort))
                .stream()
                .map(ShipmentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findCreatedAtOrBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new IllegalArgumentException("Due Shipment cutoff is required");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Due Shipment limit must be positive");
        }
        return repository.findIdsCreatedAtOrBefore(ShipmentStatus.CREATED, cutoff, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findCancelling(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Cancelling Shipment limit must be positive");
        }
        return repository.findIdsByStatus(ShipmentStatus.CANCELLING, PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public void save(Shipment shipment) {
        WmsShipmentEntity entity = repository
                .findById(shipment.id())
                .map(existing -> {
                    ShipmentMapper.updateEntity(existing, shipment);
                    return existing;
                })
                .orElseGet(() -> ShipmentMapper.toEntity(shipment));
        repository.save(entity);
    }
}
