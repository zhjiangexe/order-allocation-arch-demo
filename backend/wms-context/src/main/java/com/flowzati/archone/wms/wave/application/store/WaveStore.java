package com.flowzati.archone.wms.wave.application.store;

import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import java.util.Optional;
import java.util.UUID;

/** Wave persistence port；infrastructure implementation 不放在 wms core。 */
public interface WaveStore {

    Optional<Wave> findById(UUID waveId);

    void save(Wave wave);
}
