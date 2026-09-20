package com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.model.WaveEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWaveRepository extends JpaRepository<WaveEntity, UUID> {}
