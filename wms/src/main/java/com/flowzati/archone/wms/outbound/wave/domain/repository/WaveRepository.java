package com.flowzati.archone.wms.outbound.wave.domain.repository;

import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import java.util.Optional;
import java.util.UUID;

/** Wave persistence port；infrastructure implementation 不放在 wms core。 */
public interface WaveRepository {

    Optional<Wave> findById(UUID waveId);

    void save(Wave wave);
}
