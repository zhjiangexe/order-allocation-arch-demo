package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.ordering.application.command.ConfirmAllocationCommand;
import com.flowzati.archone.ordering.application.command.RecordBackorderCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把配貨的結果套到訂單上——**訂單狀態的唯一推進者**。
 *
 * <p>配貨不再直接改 {@code Order}：它寫自己的表、發一則事實，由這裡收到之後重讀訂單並推進。
 * 一個交易因此只修改一個 aggregate，而 {@code orders} 只有 ordering 寫。
 *
 * <p><b>事件只帶識別碼與時間戳，狀態一律由重讀決定。</b>事件裡沒有任何東西被當成訂單的狀態，
 * 所以「事件說的」與「資料庫裡的」不可能互相矛盾。
 */
@Service
public class ConfirmOrderUsecase {

  private final OrderRepository orderRepository;
  private final InboxRepo inboxRepo;
  private final ApplicationEventPublisher publisher;

  public ConfirmOrderUsecase(
      OrderRepository orderRepository,
      InboxRepo inboxRepo,
      ApplicationEventPublisher publisher
  ) {
    this.orderRepository = orderRepository;
    this.inboxRepo = inboxRepo;
    this.publisher = publisher;
  }

  @Transactional
  public void confirmAllocated(InboundCommand<ConfirmAllocationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ConfirmAllocationCommand command = inbound.command();

    Order order = orderRepository.findById(command.orderId()).orElse(null);
    if (order == null || isSettled(order)) {
      return;
    }

    order.markAllocated(command.allocatedAt());
    orderRepository.save(order);
    releaseEvents(order);
  }

  @Transactional
  public void recordBackorder(InboundCommand<RecordBackorderCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    RecordBackorderCommand command = inbound.command();

    Order order = orderRepository.findById(command.orderId()).orElse(null);
    if (order == null || isSettled(order)) {
      return;
    }

    order.markBackOrdered(command.backorderedAt());
    orderRepository.save(order);
    releaseEvents(order);
  }

  /**
   * 這張單已經走到不該再被配貨結果推動的狀態了嗎。
   *
   * <p><b>已取消是合理的競爭，不是錯誤。</b>配貨完成與使用者取消是併發的，而它們不再由同一個
   * 交易序列化：配貨可以成功並發出事件，同時取消正在處理中。把遲到的結果當成失敗，等於每一次
   * 剛好撞上的正常取消都製造一筆 DLT 訊息。allocation 側的預留已由取消事件釋放，兩邊都正確。
   *
   * <p>已配置也視為結束：重送的事件不該推第二次，而 inbox 去重擋不住「同一件事由不同 eventId
   * 送兩次」——那在補貨續做的邊界上是可能的。
   */
  private static boolean isSettled(Order order) {
    return order.getStatus() == OrderStatus.CANCELLED
        || order.getStatus() == OrderStatus.ALLOCATED;
  }

  /**
   * 推進狀態產生的領域事件照常發出。
   *
   * <p><b>它們不會再被翻譯成對外事件</b>——{@code AllocationDomainEventTranslator} 監聽的是
   * allocation 自己的事實。若哪天有人讓某個 translator 監聽 ordering 的 {@code OrderAllocated}
   * 或 {@code OrderBackordered}，就會形成「發事件 → 改狀態 → 產生事件 → 又發事件」的循環。
   */
  private void releaseEvents(Order order) {
    order.releaseDomainEvents().forEach(publisher::publishEvent);
  }
}
