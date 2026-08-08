package com.flowzati.archone.wms.outbound.wave.application.usecase;

import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.wave.application.command.PlanWaveCommand;
import com.flowzati.archone.wms.outbound.wave.domain.model.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.model.WaveAssignment;
import com.flowzati.archone.wms.outbound.wave.domain.model.WaveCandidate;
import com.flowzati.archone.wms.outbound.wave.domain.model.WavePlanningPolicy;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.outbound.wave.domain.service.WavePlanner;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 讀取尚未 release 的 Shipment snapshots，套用 planning strategy 並保存 Wave。
 *
 * <p>Composition layer 必須在同一交易內保存 Wave 與 Shipment claims；Shipment optimistic lock
 * 是避免兩個 Wave 同時選到同一 Shipment 的最後防線。
 */
public class PlanWaveUsecase {

  private final WaveRepository waveRepository;
  private final ShipmentRepository shipmentRepository;
  private final WavePlanner wavePlanner;
  private final DomainEventPublisher eventPublisher;

  public PlanWaveUsecase(
      WaveRepository waveRepository,
      ShipmentRepository shipmentRepository,
      WavePlanner wavePlanner,
      DomainEventPublisher eventPublisher
  ) {
    this.waveRepository = waveRepository;
    this.shipmentRepository = shipmentRepository;
    this.wavePlanner = wavePlanner;
    this.eventPublisher = eventPublisher;
  }

  public Wave handle(PlanWaveCommand command) {
    return waveRepository.findById(command.waveId()).orElseGet(() -> plan(command));
  }

  private Wave plan(PlanWaveCommand command) {
    WavePlanningPolicy policy = new WavePlanningPolicy(
        command.facilityId(),
        command.dispatchByCutoff(),
        command.maxShipments(),
        command.maxLines(),
        command.maxUnits());
    List<Shipment> candidateShipments = shipmentRepository
        .findWaveCandidates(command.facilityId(), command.candidateScanLimit()).stream()
        .filter(Shipment::isWaveCandidate)
        .toList();
    List<WaveCandidate> candidates = candidateShipments.stream()
        .map(WaveCandidate::from)
        .toList();
    List<WaveAssignment> assignments = wavePlanner.plan(candidates, policy);
    if (assignments.isEmpty()) {
      throw new IllegalStateException(
          "No eligible Shipment fits Wave " + command.waveId() + " planning policy");
    }

    Wave wave = Wave.plan(
        command.waveId(),
        command.facilityId(),
        command.templateCode(),
        policy,
        assignments,
        command.plannedAt());

    Map<UUID, Shipment> shipmentsById = candidateShipments.stream()
        .collect(Collectors.toMap(Shipment::id, Function.identity()));
    List<WmsDomainEvent> events = new ArrayList<>();
    for (WaveAssignment assignment : assignments) {
      Shipment shipment = shipmentsById.get(assignment.shipmentId());
      if (shipment == null) {
        throw new IllegalStateException("Wave assignment has no candidate Shipment");
      }
      shipment.assignToWave(wave.id(), command.plannedAt());
      shipmentRepository.save(shipment);
      events.addAll(shipment.releaseEvents());
    }
    waveRepository.save(wave);
    events.addAll(wave.releaseEvents());
    events.forEach(eventPublisher::publish);
    return wave;
  }
}
