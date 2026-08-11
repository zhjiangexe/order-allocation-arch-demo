package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.model.Shipment;
import com.flowzati.archone.wms.outbound.domain.model.ShipmentLine;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import java.util.List;

/** 建立尚未 release 的 Shipment demand；PickTask 必須等 Wave Release 才建立。 */
public class CreateShipmentUsecase {

  private final ShipmentRepository shipmentRepository;
  private final DomainEventPublisher eventPublisher;

  public CreateShipmentUsecase(
      ShipmentRepository shipmentRepository,
      DomainEventPublisher eventPublisher
  ) {
    this.shipmentRepository = shipmentRepository;
    this.eventPublisher = eventPublisher;
  }

  public Shipment handle(CreateShipmentCommand command) {
    return shipmentRepository.findByAllocationId(command.allocationId())
        .map(existing -> requireSameSnapshot(existing, command))
        .orElseGet(() -> create(command));
  }

  private Shipment requireSameSnapshot(Shipment existing, CreateShipmentCommand command) {
    List<ShipmentLine> expectedLines = command.lines().stream()
        .map(line -> new ShipmentLine(
            line.orderLineId(), line.moveId(), line.skuCode(),
            line.sourceLocationId(), line.quantity()))
        .toList();
    boolean same = existing.orderId().equals(command.orderId())
        && existing.ownerId().equals(command.ownerId())
        && existing.facilityId().equals(command.facilityId())
        && existing.lines().equals(expectedLines)
        && existing.dispatchBy().equals(command.dispatchBy())
        && existing.releasePriority() == command.releasePriority()
        && existing.createdAt().equals(command.createdAt());
    if (!same) {
      throw new IllegalStateException(
          "Allocation was already handed off with a different snapshot: "
              + command.allocationId());
    }
    return existing;
  }

  private Shipment create(CreateShipmentCommand command) {
    List<ShipmentLine> lines = command.lines().stream()
        .map(line -> new ShipmentLine(
            line.orderLineId(), line.moveId(), line.skuCode(),
            line.sourceLocationId(), line.quantity()))
        .toList();

    Shipment shipment = Shipment.create(
        command.shipmentId(), command.allocationId(), command.orderId(), command.ownerId(),
        command.facilityId(), lines, command.dispatchBy(), command.releasePriority(), command.createdAt());
    shipmentRepository.save(shipment);
    shipment.releaseEvents().forEach(eventPublisher::publish);
    return shipment;
  }
}
