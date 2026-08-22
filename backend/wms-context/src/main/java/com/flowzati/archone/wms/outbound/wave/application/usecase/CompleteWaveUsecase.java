package com.flowzati.archone.wms.outbound.wave.application.usecase;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.wave.application.command.CompleteWaveCommand;
import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Wave 的所有 Shipment picking work 都完成或取消後，關閉 Wave；Pack／Stage 不屬於 Wave completion。 */
public class CompleteWaveUsecase {

    private static final Logger log = LoggerFactory.getLogger(CompleteWaveUsecase.class);

    private final WaveRepository waveRepository;
    private final ShipmentRepository shipmentRepository;

    public CompleteWaveUsecase(WaveRepository waveRepository, ShipmentRepository shipmentRepository) {
        this.waveRepository = waveRepository;
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public Wave handle(CompleteWaveCommand command) {
        Wave wave = waveRepository
                .findById(command.waveId())
                .orElseThrow(() -> new IllegalStateException("Wave not found: " + command.waveId()));
        for (var assignment : wave.assignments()) {
            Shipment shipment = shipmentRepository
                    .findById(assignment.shipmentId())
                    .orElseThrow(() -> new IllegalStateException("Shipment not found: " + assignment.shipmentId()));
            if (!shipment.isPickingWorkTerminal()) {
                throw new IllegalStateException(
                        "Wave cannot complete before Shipment picking work is terminal: " + shipment.id());
            }
        }
        wave.complete(command.completedAt());
        waveRepository.save(wave);
        log.info(
                "WMS wave completed: waveId={}, shipmentCount={}",
                wave.id(),
                wave.assignments().size());
        return wave;
    }
}
