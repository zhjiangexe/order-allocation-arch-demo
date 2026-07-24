package com.flowzati.archone.allocation.entrypoint.listener;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.usecase.ReleaseReservationUsecase;
import com.flowzati.archone.ordering.application.event.OrderCancelledIntegrationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReleaseReservationListener {

  private final ReleaseReservationUsecase usecase;

  public ReleaseReservationListener(ReleaseReservationUsecase usecase) {
    this.usecase = usecase;
  }

  @EventListener
  public void onEvent(OrderCancelledIntegrationEvent event) {
    usecase.handle(new ReleaseReservationCommand(event.getOrderId()), event.getEventId());
  }
}
