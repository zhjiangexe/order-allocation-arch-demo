package com.flowzati.archone.wms.wave.application.usecase;

import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.wave.application.invocation.CompleteWaveCommand;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wave 的所有 Shipment picking work 都完成或取消後，關閉 Wave；Pack／Stage 不屬於 Wave completion。 */
@Service
public class CompleteWaveUsecase {

    private static final Logger log = LoggerFactory.getLogger(CompleteWaveUsecase.class);

    private final WaveStore waveStore;
    private final ShipmentStore shipmentStore;
    private final PickingWorkStore pickingWorkStore;

    public CompleteWaveUsecase(WaveStore waveStore, ShipmentStore shipmentStore, PickingWorkStore pickingWorkStore) {
        this.waveStore = waveStore;
        this.shipmentStore = shipmentStore;
        this.pickingWorkStore = pickingWorkStore;
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
            if (shipment.status() == ShipmentStatus.CANCELLED) {
                continue;
            }
            boolean terminal = pickingWorkStore
                    .findByShipmentId(shipment.id())
                    .map(work -> work.isTerminal())
                    .orElse(false);
            if (!terminal) {
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
