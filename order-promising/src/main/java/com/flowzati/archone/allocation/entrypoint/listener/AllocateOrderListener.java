package com.flowzati.archone.allocation.entrypoint.listener;

import com.flowzati.archone.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AllocateOrderListener {
  private final AllocateOrderUsecase usecase;

  public AllocateOrderListener(AllocateOrderUsecase usecase) {
    this.usecase = usecase;
  }

  @EventListener
  public void onEvent(OrderPlaced event) {
    usecase.handle(event);
  }
}
