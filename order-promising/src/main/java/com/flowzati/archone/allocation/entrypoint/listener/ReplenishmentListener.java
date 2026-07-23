package com.flowzati.archone.allocation.entrypoint.listener;

import com.flowzati.archone.allocation.application.usecase.ReplenishmentUsecase;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReplenishmentListener {
  private final ReplenishmentUsecase usecase;

  public ReplenishmentListener(ReplenishmentUsecase usecase) {
    this.usecase = usecase;
  }

  @EventListener
  public void onEvent(StockReplenishedIntegrationEvent event) {
    usecase.handle(event);
  }
}
