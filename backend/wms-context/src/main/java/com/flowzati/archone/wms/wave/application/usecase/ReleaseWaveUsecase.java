package com.flowzati.archone.wms.wave.application.usecase;

import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.wave.application.event.WaveReleased;
import com.flowzati.archone.wms.wave.application.invocation.ReleaseWaveCommand;
import com.flowzati.archone.wms.wave.application.port.WaveReleasedPublisher;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.type.WaveStatus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 將已規劃 Wave 凍結並產生現場 picking work。
 *
 * <p>同步 domain event handler 與本 use case 共用交易，確保 Wave、Shipments 與 PickingWork 一起提交。
 * 第一版每張尚未取消的 Shipment 建一個 PickingWork；Wave 規劃後、release 前取消的 Shipment
 * 會保留 assignment audit trail，但不再建立現場工作。
 */
public class ReleaseWaveUsecase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseWaveUsecase.class);

    private final WaveStore waveStore;
    private final ShipmentStore shipmentStore;
    private final WaveReleasedPublisher waveReleasedPublisher;

    public ReleaseWaveUsecase(
            WaveStore waveStore, ShipmentStore shipmentStore, WaveReleasedPublisher waveReleasedPublisher) {
        this.waveStore = waveStore;
        this.shipmentStore = shipmentStore;
        this.waveReleasedPublisher = waveReleasedPublisher;
    }

    @Transactional
    public Wave handle(ReleaseWaveCommand command) {
        Wave wave = requiredWave(command);
        if (wave.status() != WaveStatus.PLANNED) {
            return wave;
        }

        List<Shipment> activeShipments = wave.assignments().stream()
                .map(assignment -> requiredShipment(assignment.shipmentId()))
                .filter(shipment -> shipment.status() != ShipmentStatus.CANCELLED)
                .toList();
        int pickingWorkCount = activeShipments.size();
        int pickTaskCount = activeShipments.stream()
                .map(shipment -> shipment.lines().size())
                .reduce(0, Math::addExact);

        wave.release(pickingWorkCount, pickTaskCount, command.releasedAt());
        waveStore.save(wave);
        waveReleasedPublisher.publish(new WaveReleased(
                wave.id(),
                wave.assignments().stream()
                        .map(assignment -> assignment.shipmentId())
                        .toList(),
                command.releasedAt()));
        log.info(
                "WMS wave released: waveId={}, pickingWorkCount={}, pickTaskCount={}",
                wave.id(),
                pickingWorkCount,
                pickTaskCount);
        return wave;
    }

    private Wave requiredWave(ReleaseWaveCommand command) {
        return waveStore
                .findById(command.waveId())
                .orElseThrow(() -> new IllegalStateException("Wave not found: " + command.waveId()));
    }

    private Shipment requiredShipment(java.util.UUID shipmentId) {
        return shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
