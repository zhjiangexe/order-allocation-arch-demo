package com.flowzati.archone.inventory.allocation.application.usecase;

import com.flowzati.archone.inventory.allocation.application.invocation.ReleaseStockOperationCommand;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationReleaseService;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.inventory.movement.application.port.StockOperationLifecycleChangedPublisher;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 釋放一張作業目前的預留（保留作業與 move 的身分），並發布「已釋放」。 */
@Service
public class ReleaseStockOperationUsecase {

    private final StockOperationReleaseService stockOperationReleaseService;
    private final StockOperationLifecycleChangedPublisher lifecyclePublisher;

    public ReleaseStockOperationUsecase(
            StockOperationReleaseService stockOperationReleaseService,
            StockOperationLifecycleChangedPublisher lifecyclePublisher) {
        this.stockOperationReleaseService = stockOperationReleaseService;
        this.lifecyclePublisher = lifecyclePublisher;
    }

    /**
     * @return {@code true} when this call releases an assigned group; {@code false} when the group is
     *     already confirmed and holds no reservation.
     */
    @Transactional
    public boolean execute(ReleaseStockOperationCommand command) {
        Optional<StockOperationLifecycleSnapshot> released =
                stockOperationReleaseService.release(command.stockOperationId(), command.occurredAt());
        released.ifPresent(snapshot -> lifecyclePublisher.publish(StockOperationLifecycleChanged.released(snapshot)));
        return released.isPresent();
    }
}
