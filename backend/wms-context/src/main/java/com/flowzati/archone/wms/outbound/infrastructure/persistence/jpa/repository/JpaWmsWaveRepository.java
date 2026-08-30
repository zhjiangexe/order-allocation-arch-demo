package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.repository;

import com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model.WmsWaveEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWmsWaveRepository extends JpaRepository<WmsWaveEntity, UUID> {}
