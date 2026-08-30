package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.CompleteWaveCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import com.flowzati.archone.wms.outbound.application.store.WaveStore;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.aggregate.Wave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/** Wave 的所有 Shipment picking work 都完成或取消後，關閉 Wave；Pack／Stage 不屬於 Wave completion。 */
public class CompleteWaveUsecase {

    private static final Logger log = LoggerFactory.getLogger(CompleteWaveUsecase.class);

    private final WaveStore waveStore;
    private final ShipmentStore shipmentStore;

    public CompleteWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore) {
        this.waveStore = waveStore;
        this.shipmentStore = shipmentStore;
    }

    @Transactional
    public Wave handle(CompleteWaveCommand command) {
        Wave wave = waveStore
                .findById(command.waveId())
                .orElseThrow(() -> new IllegalStateException("Wave not found: " + command.waveId()));
        for (var assignment : wave.assignments()) {
            Shipment shipment = shipmentStore
                    .findById(assignment.shipmentId())
                    .orElseThrow(() -> new IllegalStateException("Shipment not found: " + assignment.shipmentId()));
            if (!shipment.isPickingWorkTerminal()) {
                throw new IllegalStateException(
                        "Wave cannot complete before Shipment picking work is terminal: " + shipment.id());
            }
        }
        wave.complete(command.completedAt());
        waveStore.save(wave);
        log.info(
                "WMS wave completed: waveId={}, shipmentCount={}",
                wave.id(),
                wave.assignments().size());
        return wave;
    }
}
