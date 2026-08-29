package com.flowzati.archone.wms.outbound.infrastructure.persistence.repository;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.entity.WmsShipmentEntity;
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
public class JpaShipmentRepositoryAdapter implements ShipmentRepository {

    private final JpaWmsShipmentRepository repository;

    public JpaShipmentRepositoryAdapter(JpaWmsShipmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findById(UUID shipmentId) {
        return repository.findById(shipmentId).map(WmsShipmentEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByStockOperationId(UUID stockOperationId) {
        return repository.findByStockOperationId(stockOperationId).map(WmsShipmentEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shipment> findByOrderId(UUID orderId) {
        return repository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(WmsShipmentEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByPickTaskId(UUID pickTaskId) {
        return repository.findByPickTaskId(pickTaskId).map(WmsShipmentEntity::toDomain);
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
                .map(WmsShipmentEntity::toDomain)
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
                    existing.replaceFrom(shipment);
                    return existing;
                })
                .orElseGet(() -> new WmsShipmentEntity(shipment));
        repository.save(entity);
    }
}
