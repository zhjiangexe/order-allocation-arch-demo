package com.flowzati.archone.wms.outbound.wave.application.usecase;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.wave.application.command.CompleteWaveCommand;
import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;

/** Wave 的所有 Shipment picking work 都完成或取消後，關閉 Wave；Pack／Stage 不屬於 Wave completion。 */
public class CompleteWaveUsecase {

    private final WaveRepository waveRepository;
    private final ShipmentRepository shipmentRepository;
    private final DomainEventPublisher eventPublisher;

    public CompleteWaveUsecase(
            WaveRepository waveRepository, ShipmentRepository shipmentRepository, DomainEventPublisher eventPublisher) {
        this.waveRepository = waveRepository;
        this.shipmentRepository = shipmentRepository;
        this.eventPublisher = eventPublisher;
    }

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
        wave.releaseEvents().forEach(eventPublisher::publish);
        return wave;
    }
}
