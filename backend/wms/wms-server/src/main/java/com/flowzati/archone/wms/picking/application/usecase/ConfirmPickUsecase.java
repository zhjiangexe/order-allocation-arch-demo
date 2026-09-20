package com.flowzati.archone.wms.picking.application.usecase;

import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;
import com.flowzati.archone.wms.picking.application.invocation.ConfirmPickCommand;
import com.flowzati.archone.wms.picking.application.port.PickingWorkStatusChangedPublisher;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmPickUsecase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPickUsecase.class);

    private final PickingWorkStore pickingWorkStore;
    private final PickingWorkStatusChangedPublisher pickingStatusChangedPublisher;

    public ConfirmPickUsecase(
            PickingWorkStore pickingWorkStore, PickingWorkStatusChangedPublisher pickingStatusChangedPublisher) {
        this.pickingWorkStore = pickingWorkStore;
        this.pickingStatusChangedPublisher = pickingStatusChangedPublisher;
    }

    @Transactional
    public void handle(ConfirmPickCommand command) {
        PickingWork work = pickingWorkStore
                .findByPickTaskId(command.pickTaskId())
                .orElseThrow(() -> new IllegalStateException("PickingWork not found: " + command.pickTaskId()));
        var previousStatus = work.status();
        work.confirmPick(command.pickTaskId(), command.actualQuantity(), command.confirmedAt());
        pickingWorkStore.save(work);
        if (work.status() != previousStatus) {
            pickingStatusChangedPublisher.publish(
                    new PickingWorkStatusChanged(work.id(), work.shipmentId(), work.status(), command.confirmedAt()));
        }
        log.info(
                "WMS pick confirmed: pickingWorkId={}, shipmentId={}, pickTaskId={}, actualQuantity={}, status={}",
                work.id(),
                work.shipmentId(),
                command.pickTaskId(),
                command.actualQuantity(),
                work.status());
    }
}
