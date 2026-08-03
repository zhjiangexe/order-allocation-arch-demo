package com.flowzati.archone.ordering.application.usecase;

import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.ordering.application.command.ConfirmAllocationCommand;
import com.flowzati.archone.ordering.application.command.RecordBackorderCommand;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 把配貨的結果套到訂單上——**訂單狀態的唯一推進者**。
 *
 * <p>配貨不再直接改 {@code Order}：它寫自己的表、發一則事實，由這裡收到之後重讀訂單並推進。
 * 一個交易因此只修改一個 aggregate，而 {@code orders} 只有 ordering 寫。
 *
 * <p><b>事件只帶識別碼與時間戳，狀態一律由重讀決定。</b>事件裡沒有任何東西被當成訂單的狀態，
 * 所以「事件說的」與「資料庫裡的」不可能互相矛盾。
 *
 * <p>這裡只更新 ordering 的查詢投影，不另行發布 ordering 事件；配貨結果的來源事實已由
 * stock 發布。
 */
@Service
public class ConfirmOrderUsecase {

  private final OrderRepository orderRepository;
  private final InboxRepo inboxRepo;

  public ConfirmOrderUsecase(OrderRepository orderRepository, InboxRepo inboxRepo) {
    this.orderRepository = orderRepository;
    this.inboxRepo = inboxRepo;
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
  }

  @Transactional
  public void recordBackorder(InboundCommand<RecordBackorderCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    RecordBackorderCommand command = inbound.command();

    Optional<Order> orderOpt = orderRepository.findById(command.orderId());
    if (orderOpt.isEmpty()) {
      return;
    }
    Order order = orderOpt.get();
    // 已經是缺貨就不再推一次。理由與 isSettled 相同（重送擋不住不同 eventId），但它不是「結束」
    // ——缺貨還在等補貨。分開寫是為了不讓 isSettled 這個名字說謊。
    if (isSettled(order) || order.getStatus() == OrderStatus.BACKORDERED) {
      return;
    }
    order.markBackOrdered(command.backorderedAt());
    orderRepository.save(order);
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
}
