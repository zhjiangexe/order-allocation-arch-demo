package com.flowzati.archone.allocation.entrypoint.listener;

import com.flowzati.archone.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.ordering.application.event.OrderPlacedIntegrationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AllocateOrderListener {
  private final AllocateOrderUsecase usecase;

  public AllocateOrderListener(AllocateOrderUsecase usecase) {
    this.usecase = usecase;
  }

  @EventListener
  public void onEvent(OrderPlacedIntegrationEvent event) {
    usecase.handle(new AllocateOrderCommand(event.getOrderId()), event.getEventId());
  }
}
