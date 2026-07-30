package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import jakarta.transaction.Transactional;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReleaseReservationUsecase {

  private final InboxRepo inboxRepo;
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;

  public ReleaseReservationUsecase(
      InboxRepo inboxRepo,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboundCommand<ReleaseReservationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ReleaseReservationCommand command = inbound.command();

    // 先取行的 id 再查預留：stock_reservations 指向 order_lines，用訂單查就得 join 到
    // ordering 的表，而那個方向的依賴不該由 allocation 的 repository 建立。
    Order order = orderRepository.findById(command.orderId()).orElse(null);
    if (order == null) {
      return;
    }
    List<UUID> orderLineIds = order.getLines().stream().map(OrderLine::getId).toList();

    // 一條行跨三批就有三筆預留，全部都要釋放。只放第一筆的話其餘批的量會永遠鎖著，而且
    // 不會有任何錯誤浮現——庫存看起來只是莫名其妙少了一些。
    List<StockReservation> reservations =
        stockReservationRepository.findActiveByOrderLineIds(orderLineIds);
    if (reservations.isEmpty()) {
      return;
    }

    Map<UUID, StockPool> batchesById = new LinkedHashMap<>();
    for (StockReservation reservation : reservations) {
      batchesById.computeIfAbsent(reservation.getStockPoolId(), id ->
          stockPoolRepository.findById(id)
              .orElseThrow(() -> new IllegalStateException("StockPool not found: " + id)));
    }

    Instant now = clock.instant();
    allocationCoordinator.releaseReservations(reservations, batchesById, now);
  }
}
