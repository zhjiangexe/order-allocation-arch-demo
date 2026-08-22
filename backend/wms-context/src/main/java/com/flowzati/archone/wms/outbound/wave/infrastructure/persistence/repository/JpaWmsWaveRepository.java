package com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.repository;

import com.flowzati.archone.wms.outbound.wave.infrastructure.persistence.entity.WmsWaveEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWmsWaveRepository extends JpaRepository<WmsWaveEntity, UUID> {}
