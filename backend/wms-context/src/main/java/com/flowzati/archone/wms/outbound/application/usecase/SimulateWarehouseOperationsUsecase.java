package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.wms.outbound.application.command.ConfirmPickCommand;
import com.flowzati.archone.wms.outbound.application.command.HandOverShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.PackShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.outbound.application.command.StageShipmentCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.wave.application.command.CompleteWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.command.PlanWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.command.ReleaseWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.ReleaseWaveUsecase;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 現階段的模擬倉內作業：使用 Shipment 既有 invariant，一次完成 Wave、Pick、Pack、Stage 與 handover。
 *
 * <p>這個 adapter 刻意串接正式的細粒度 use cases，讓模擬環境與真實操作入口共享相同的 transaction、
 * persistence 與 invariant。整段在單一 transaction 內提交，服務失敗時不會留下只完成一半的模擬作業。
 */
public class SimulateWarehouseOperationsUsecase {

    private static final Logger log = LoggerFactory.getLogger(SimulateWarehouseOperationsUsecase.class);

    private final ShipmentRepository shipmentRepository;
    private final PlanWaveUsecase planWaveUsecase;
    private final ReleaseWaveUsecase releaseWaveUsecase;
    private final ConfirmPickUsecase confirmPickUsecase;
    private final CompleteWaveUsecase completeWaveUsecase;
    private final PackShipmentUsecase packShipmentUsecase;
    private final StageShipmentUsecase stageShipmentUsecase;
    private final HandOverShipmentUsecase handOverShipmentUsecase;

    public SimulateWarehouseOperationsUsecase(
            ShipmentRepository shipmentRepository,
            PlanWaveUsecase planWaveUsecase,
            ReleaseWaveUsecase releaseWaveUsecase,
            ConfirmPickUsecase confirmPickUsecase,
            CompleteWaveUsecase completeWaveUsecase,
            PackShipmentUsecase packShipmentUsecase,
            StageShipmentUsecase stageShipmentUsecase,
            HandOverShipmentUsecase handOverShipmentUsecase) {
        this.shipmentRepository = shipmentRepository;
        this.planWaveUsecase = planWaveUsecase;
        this.releaseWaveUsecase = releaseWaveUsecase;
        this.confirmPickUsecase = confirmPickUsecase;
        this.completeWaveUsecase = completeWaveUsecase;
        this.packShipmentUsecase = packShipmentUsecase;
        this.stageShipmentUsecase = stageShipmentUsecase;
        this.handOverShipmentUsecase = handOverShipmentUsecase;
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
            log.info(
                    "WMS simulated warehouse operation skipped: shipmentId={}, status={}",
                    shipment.id(),
                    shipment.status());
            return false;
        }

        UUID waveId = IdGenerator.nextId();
        int lineCount = shipment.lines().size();
        int unitCount =
                shipment.lines().stream().mapToInt(line -> line.quantity()).sum();
        planWaveUsecase.handle(new PlanWaveCommand(
                waveId,
                shipment.facilityId(),
                "SIMULATED",
                shipment.dispatchBy(),
                1,
                1,
                lineCount,
                unitCount,
                command.processedAt(),
                List.of(shipment.id())));
        releaseWaveUsecase.handle(new ReleaseWaveCommand(waveId, command.processedAt()));

        Shipment releasedShipment = requiredShipment(command.shipmentId());
        releasedShipment
                .pickTasks()
                .forEach(task -> confirmPickUsecase.handle(
                        new ConfirmPickCommand(task.id(), task.requestedQuantity(), command.processedAt())));
        completeWaveUsecase.handle(new CompleteWaveCommand(waveId, command.processedAt()));
        packShipmentUsecase.handle(new PackShipmentCommand(shipment.id(), command.processedAt()));
        stageShipmentUsecase.handle(new StageShipmentCommand(shipment.id(), command.processedAt()));
        handOverShipmentUsecase.handle(new HandOverShipmentCommand(shipment.id(), command.processedAt()));

        log.info("WMS simulated warehouse operation completed: shipmentId={}, waveId={}", shipment.id(), waveId);
        return true;
    }

    private Shipment requiredShipment(UUID shipmentId) {
        return shipmentRepository
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
