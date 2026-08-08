package com.flowzati.archone.wms.outbound.wave.domain.service;

import com.flowzati.archone.wms.outbound.wave.domain.model.WaveAssignment;
import com.flowzati.archone.wms.outbound.wave.domain.model.WaveCandidate;
import com.flowzati.archone.wms.outbound.wave.domain.model.WavePlanningPolicy;
import java.util.List;

/** 可替換的 Wave planning strategy。 */
@FunctionalInterface
public interface WavePlanner {

  List<WaveAssignment> plan(List<WaveCandidate> candidates, WavePlanningPolicy policy);
}
