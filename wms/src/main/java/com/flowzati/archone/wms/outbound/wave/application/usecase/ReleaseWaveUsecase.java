package com.flowzati.archone.wms.outbound.wave.application.usecase;

import com.flowzati.archone.wms.outbound.domain.model.PickTask;
import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.model.ShipmentLine;
import com.flowzati.archone.wms.outbound.domain.model.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.model.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.wave.application.command.ReleaseWaveCommand;
import com.flowzati.archone.wms.outbound.wave.domain.model.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.model.WaveStatus;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 將已規劃 Wave 凍結並產生現場 picking work。
 *
 * <p>Composition layer 必須把本 use case 包在同一資料庫交易與 Outbox 邊界內，確保 Wave、Shipments、
 * WarehouseWork 與 events 一起提交。第一版每張尚未取消的 Shipment 建一個 WarehouseWork；Wave 規劃後、
 * release 前取消的 Shipment 會保留 assignment audit trail，但不再建立現場工作。
 */
public class ReleaseWaveUsecase {

  private final WaveRepository waveRepository;
  private final ShipmentRepository shipmentRepository;
  private final IdGenerator idGenerator;
  private final DomainEventPublisher eventPublisher;

  public ReleaseWaveUsecase(
      WaveRepository waveRepository,
      ShipmentRepository shipmentRepository,
      IdGenerator idGenerator,
      DomainEventPublisher eventPublisher
  ) {
    this.waveRepository = waveRepository;
    this.shipmentRepository = shipmentRepository;
    this.idGenerator = idGenerator;
    this.eventPublisher = eventPublisher;
  }

  public Wave handle(ReleaseWaveCommand command) {
    Wave wave = requiredWave(command);
    if (wave.status() != WaveStatus.PLANNED) {
      return wave;
    }

    List<WmsDomainEvent> events = new ArrayList<>();
    int warehouseWorkCount = 0;
    int pickTaskCount = 0;
    for (var assignment : wave.assignments()) {
      Shipment shipment = requiredShipment(assignment.shipmentId());
      if (shipment.status() == ShipmentStatus.CANCELLED) {
        continue;
      }
      WarehouseWork work = existingOrCreatePickingWork(wave, shipment);
      warehouseWorkCount = Math.addExact(warehouseWorkCount, 1);
      pickTaskCount = Math.addExact(pickTaskCount, work.pickTasks().size());
      shipment.releaseToWave(wave.id(), work, command.releasedAt());
      shipmentRepository.save(shipment);
      events.addAll(shipment.releaseEvents());
    }

    wave.release(warehouseWorkCount, pickTaskCount, command.releasedAt());
    waveRepository.save(wave);
    events.addAll(wave.releaseEvents());
    events.forEach(eventPublisher::publish);
    return wave;
  }

  private WarehouseWork existingOrCreatePickingWork(Wave wave, Shipment shipment) {
    return shipment.pickingWork()
        .map(existing -> {
          if (!existing.belongsTo(wave.id(), shipment.id())) {
            throw new IllegalStateException("Shipment was already released by another Wave");
          }
          return existing;
        })
        .orElseGet(() -> createPickingWork(wave, shipment));
  }

  private WarehouseWork createPickingWork(Wave wave, Shipment shipment) {
    List<PickTask> tasks = shipment.lines().stream()
        .map(this::createPickTask)
        .toList();
    return new WarehouseWork(
        idGenerator.nextId(), wave.id(), shipment.id(), tasks);
  }

  private PickTask createPickTask(ShipmentLine line) {
    return new PickTask(
        idGenerator.nextId(),
        line.orderLineId(),
        line.moveId(),
        line.skuCode(),
        line.sourceLocationId(),
        line.quantity());
  }

  private Wave requiredWave(ReleaseWaveCommand command) {
    return waveRepository.findById(command.waveId())
        .orElseThrow(() -> new IllegalStateException("Wave not found: " + command.waveId()));
  }

  private Shipment requiredShipment(java.util.UUID shipmentId) {
    return shipmentRepository.findById(shipmentId)
        .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
  }
}
