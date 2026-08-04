package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 單一訂單的對外表示，由下單、最近訂單列表與單筆查詢三處共用——客戶端因此只需要一個
 * 訂單模型，而不是「建立時拿到一種、查詢時拿到另一種」。
 *
 * <p>只帶 {@code ownerId}，不帶貨主名稱。名稱由呼叫端自己解析——它本來就要載 {@code /owners}
 * 填下單表單的下拉選單，那份資料在同一個畫面上已經在手上了。這不是 N+1：N+1 是「每一列各打
 * 一次」，而這是「整個畫面打一次」。把名稱塞進訂單契約只會讓每次列表多一次主檔查詢，換到的
 * 是呼叫端本來就有的東西。
 *
 * <p>SKU 與數量在 {@code lines} 裡而不在頂層：訂單的形狀本來就是行的集合，客戶端不需要
 * 知道「目前每張單只有一行」這件事，多行放寬時這個契約也不必改。
 *
 * <p>倉別在頂層而不在行上：一張單只從一個倉出、明細不可跨倉，放在行上會是 header 的複本。
 * 與貨主名稱同理，這裡只帶識別碼——倉庫名稱由呼叫端從它已載入的主檔解析。
 *
 * <p><b>兩個時間戳，兩件事。</b>{@code receivedAt} 是我們收到這張單的時刻，永遠有值；
 * {@code placedAt} 是上游說客戶下單的時刻，上游沒送時為 {@code null}。**不會用前者補後者**
 * ——補了就與「上游真的送了同一個時間」在畫面上長得一樣。
 *
 * <p>這個欄位名曾經指的是收單時刻。凡是讀 {@code placedAt} 的呼叫端都要確認自己要的是哪一
 * 個：它現在可能是 null，而且由一個我們控制不了時鐘的系統決定。要排序請用 {@code receivedAt}。
 *
 * <p>k6 polls this to measure "time to allocation decision": the exact
 * {@code allocatedAt}/{@code backOrderedSince} timestamp, not the polling interval, is
 * what should drive the latency chart — polling only tells k6 *when to stop asking*.
 */
public record OrderStatusResponse(
    UUID orderId,
    UUID ownerId,
    String externalOrderNo,
    UUID facilityId,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    List<Line> lines,
    String status,
    Instant receivedAt,
    Instant placedAt,
    Instant allocatedAt,
    Instant backOrderedSince,
    Instant cancelledAt
) {

  /** {@code status} 隨 header 走（ship-complete），保留是為了讓多行時的畫面不必改契約。 */
  public record Line(int lineNo, String skuCode, int quantity, String status) {
  }

  static OrderStatusResponse from(Order order) {
    return new OrderStatusResponse(
        order.getId(),
        order.getOwnerId(),
        order.getExternalOrderNo(),
        order.getDeliveryTerms().facilityId(),
        order.getDeliveryTerms().shipToZone(),
        order.getDeliveryTerms().shipToAddress(),
        order.getDeliveryTerms().promisedDeliveryDate(),
        order.getLines().stream().map(line -> toLine(line, order.getStatus())).toList(),
        order.getStatus().name(),
        order.getReceivedAt(),
        order.getPlacedAt(),
        order.getAllocatedAt(),
        order.getBackOrderedSince(),
        order.getCancelledAt());
  }

  /**
   * 逐行的狀態**由 header 導出**，不是行自己存的。
   *
   * <p>ship-complete 之下一張單的所有行同進同出，所以那個值恆等於 header——存在行上是同一份
   * 資料存兩次。契約保留這個欄位（值一個字沒變，前端因此不動），但它現在只有一個可能出錯的
   * 地方，而不是兩個。
   */
  private static Line toLine(OrderLine line, OrderStatus orderStatus) {
    return new Line(line.getLineNo(), line.getSkuCode(), line.getQuantity(), orderStatus.name());
  }
}
