package com.flowzati.archone.wms.outbound.wave.domain.service;

import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveCandidate;
import com.flowzati.archone.wms.outbound.wave.domain.policy.WavePlanningPolicy;
import java.util.List;

/** 可替換的 Wave planning strategy。 */
@FunctionalInterface
public interface WavePlanner {

  List<WaveAssignment> plan(List<WaveCandidate> candidates, WavePlanningPolicy policy);
}
