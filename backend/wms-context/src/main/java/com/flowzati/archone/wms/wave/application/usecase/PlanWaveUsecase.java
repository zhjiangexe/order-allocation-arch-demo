package com.flowzati.archone.wms.wave.application.usecase;

import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.wave.application.invocation.PlanWaveCommand;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.service.WavePlanner;
import com.flowzati.archone.wms.wave.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.wave.domain.valueobject.WaveCandidate;
import com.flowzati.archone.wms.wave.domain.valueobject.WavePlanningPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 讀取尚未 release 的 Shipment snapshots，套用 planning strategy 並保存 Wave。
 *
 * <p>Composition layer 必須在同一交易內保存 Wave 與 Shipment claims；Shipment optimistic lock
 * 是避免兩個 Wave 同時選到同一 Shipment 的最後防線。
 */
@Service
public class PlanWaveUsecase {

    private static final Logger log = LoggerFactory.getLogger(PlanWaveUsecase.class);

    private final WaveStore waveStore;
    private final ShipmentStore shipmentStore;
    private final WavePlanner wavePlanner;

    public PlanWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore, WavePlanner wavePlanner) {
        this.waveStore = waveStore;
        this.shipmentStore = shipmentStore;
        this.wavePlanner = wavePlanner;
    }

    @Transactional
    public Wave handle(PlanWaveCommand command) {
        return waveStore.findById(command.waveId()).orElseGet(() -> plan(command));
    }

    private Wave plan(PlanWaveCommand command) {
        WavePlanningPolicy policy = new WavePlanningPolicy(
                command.facilityId(),
                command.dispatchByCutoff(),
                command.maxShipments(),
                command.maxLines(),
                command.maxUnits());
        List<Shipment> candidateShipments = command.shipmentIds().isEmpty()
                ? shipmentStore.findWaveCandidates(command.facilityId(), command.candidateScanLimit()).stream()
                        .filter(Shipment::isWaveCandidate)
                        .toList()
                : command.shipmentIds().stream()
                        .map(shipmentId -> shipmentStore
                                .findById(shipmentId)
                                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId)))
                        .filter(shipment -> shipment.facilityId().equals(command.facilityId()))
                        .filter(Shipment::isWaveCandidate)
                        .toList();
        List<WaveCandidate> candidates =
                candidateShipments.stream().map(WaveCandidate::from).toList();
        List<WaveAssignment> assignments = wavePlanner.plan(candidates, policy);
        if (assignments.isEmpty()) {
            throw new IllegalStateException("No eligible Shipment fits Wave " + command.waveId() + " planning policy");
        }

        Wave wave = Wave.plan(
                command.waveId(),
                command.facilityId(),
                command.templateCode(),
                policy,
                assignments,
                command.plannedAt());

        Map<UUID, Shipment> shipmentsById =
                candidateShipments.stream().collect(Collectors.toMap(Shipment::id, Function.identity()));
        for (WaveAssignment assignment : assignments) {
            Shipment shipment = shipmentsById.get(assignment.shipmentId());
            if (shipment == null) {
                throw new IllegalStateException("Wave assignment has no candidate Shipment");
            }
            shipment.assignToWave(wave.id(), command.plannedAt());
            shipmentStore.save(shipment);
        }
        waveStore.save(wave);
        log.info(
                "WMS wave planned: waveId={}, facilityId={}, shipmentCount={}",
                wave.id(),
                wave.facilityId(),
                assignments.size());
        return wave;
    }
}
