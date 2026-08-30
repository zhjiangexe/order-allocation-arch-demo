package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.outbound.application.store.WaveStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.mapper.WaveMapper;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsWaveEntity;
import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.repository.JpaWmsWaveRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaWaveStoreAdapter implements WaveStore {

    private final JpaWmsWaveRepository repository;

    public JpaWaveStoreAdapter(JpaWmsWaveRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Wave> findById(UUID waveId) {
        return repository.findById(waveId).map(WaveMapper::toDomain);
    }

    @Override
    @Transactional
    public void save(Wave wave) {
        WmsWaveEntity entity = repository
                .findById(wave.id())
                .map(existing -> {
                    WaveMapper.updateEntity(existing, wave);
                    return existing;
                })
                .orElseGet(() -> WaveMapper.toEntity(wave));
        repository.save(entity);
    }
}
