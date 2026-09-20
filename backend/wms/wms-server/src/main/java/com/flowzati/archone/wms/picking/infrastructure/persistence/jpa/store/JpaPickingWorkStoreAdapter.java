package com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.mapper.PickingWorkMapper;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.model.PickingWorkEntity;
import com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.repository.JpaPickingWorkRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaPickingWorkStoreAdapter implements PickingWorkStore {

    private final JpaPickingWorkRepository repository;

    public JpaPickingWorkStoreAdapter(JpaPickingWorkRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PickingWork> findByShipmentId(UUID shipmentId) {
        return repository.findByShipmentId(shipmentId).map(PickingWorkMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PickingWork> findByPickTaskId(UUID pickTaskId) {
        return repository.findByPickTaskId(pickTaskId).map(PickingWorkMapper::toDomain);
    }

    @Override
    @Transactional
    public void save(PickingWork work) {
        PickingWorkEntity entity = repository
                .findById(work.id())
                .map(existing -> {
                    PickingWorkMapper.updateEntity(existing, work);
                    return existing;
                })
                .orElseGet(() -> PickingWorkMapper.toEntity(work));
        repository.save(entity);
    }
}
