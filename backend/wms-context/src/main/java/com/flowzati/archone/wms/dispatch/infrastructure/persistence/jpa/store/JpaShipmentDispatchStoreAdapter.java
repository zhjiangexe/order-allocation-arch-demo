package com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.mapper.ShipmentDispatchMapper;
import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.model.ShipmentDispatchEntity;
import com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.repository.JpaShipmentDispatchRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaShipmentDispatchStoreAdapter implements ShipmentDispatchStore {

    private final JpaShipmentDispatchRepository repository;

    public JpaShipmentDispatchStoreAdapter(JpaShipmentDispatchRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentDispatch> findByShipmentId(UUID shipmentId) {
        return repository.findByShipmentId(shipmentId).map(ShipmentDispatchMapper::toDomain);
    }

    @Override
    @Transactional
    public void save(ShipmentDispatch shipmentDispatch) {
        ShipmentDispatchEntity entity = repository
                .findById(shipmentDispatch.id())
                .map(existing -> {
                    ShipmentDispatchMapper.updateEntity(existing, shipmentDispatch);
                    return existing;
                })
                .orElseGet(() -> ShipmentDispatchMapper.toEntity(shipmentDispatch));
        repository.save(entity);
    }
}
