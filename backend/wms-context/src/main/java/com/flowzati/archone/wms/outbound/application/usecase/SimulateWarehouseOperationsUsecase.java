package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.command.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 現階段的模擬倉內作業：使用 Shipment 既有 invariant，一次完成 Wave、Pick、Pack、Stage 與 handover。
 *
 * <p>本專案目前不實作真正 WMS，也尚未提供 Wave 的 production persistence adapter。因此這裡建立
 * synthetic Wave／WarehouseWork identity，但仍逐步呼叫 Shipment domain 行為，不直接竄改 status。
 * 整段在單一 transaction 內提交，服務失敗時不會留下只完成一半的模擬作業。
 */
public class SimulateWarehouseOperationsUsecase {

    private final ShipmentRepository shipmentRepository;
    private final IdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;

    public SimulateWarehouseOperationsUsecase(
            ShipmentRepository shipmentRepository, IdGenerator idGenerator, DomainEventPublisher eventPublisher) {
        this.shipmentRepository = shipmentRepository;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return {@code true} 代表本次完成模擬；若 Shipment 已被取消或其他 instance 已先處理則回傳
     *     {@code false}。
     */
    @Transactional
    public boolean handle(SimulateWarehouseOperationsCommand command) {
        Shipment shipment = shipmentRepository
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        if (shipment.status() != ShipmentStatus.CREATED) {
            return false;
        }

        Instant processedAt = command.processedAt();
        UUID waveId = idGenerator.nextId();
        shipment.assignToWave(waveId, processedAt);

        WarehouseWork work = createPickingWork(shipment, waveId);
        shipment.releaseToWave(waveId, work, processedAt);
        work.pickTasks().forEach(task -> shipment.confirmPick(task.id(), task.requestedQuantity(), processedAt));
        shipment.pack(processedAt);
        shipment.stage(processedAt);
        shipment.handOverToCarrier(processedAt);

        shipmentRepository.save(shipment);
        shipment.releaseEvents().forEach(eventPublisher::publish);
        return true;
    }

    private WarehouseWork createPickingWork(Shipment shipment, UUID waveId) {
        List<PickTask> tasks = shipment.lines().stream()
                .map(line -> new PickTask(
                        idGenerator.nextId(),
                        line.orderLineId(),
                        line.moveId(),
                        line.skuCode(),
                        line.sourceLocationId(),
                        line.quantity()))
                .toList();
        return new WarehouseWork(idGenerator.nextId(), waveId, shipment.id(), tasks);
    }
}
