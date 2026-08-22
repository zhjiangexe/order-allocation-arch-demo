package com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.repository;

import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.entity.WmsWaveEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaWaveRepositoryAdapter implements WaveRepository {

    private final JpaWmsWaveRepository repository;

    public JpaWaveRepositoryAdapter(JpaWmsWaveRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Wave> findById(UUID waveId) {
        return repository.findById(waveId).map(WmsWaveEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(Wave wave) {
        WmsWaveEntity entity = repository
                .findById(wave.id())
                .map(existing -> {
                    existing.replaceFrom(wave);
                    return existing;
                })
                .orElseGet(() -> new WmsWaveEntity(wave));
        repository.save(entity);
    }
}
