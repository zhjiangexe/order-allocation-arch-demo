package com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.store;

import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.mapper.WaveMapper;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.model.WaveEntity;
import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.repository.JpaWaveRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaWaveStoreAdapter implements WaveStore {

    private final JpaWaveRepository repository;

    public JpaWaveStoreAdapter(JpaWaveRepository repository) {
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
        WaveEntity entity = repository
                .findById(wave.id())
                .map(existing -> {
                    WaveMapper.updateEntity(existing, wave);
                    return existing;
                })
                .orElseGet(() -> WaveMapper.toEntity(wave));
        repository.save(entity);
    }
}
