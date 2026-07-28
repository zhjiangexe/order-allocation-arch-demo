package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderLine;
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
 * <p>k6 polls this to measure "time to allocation decision": the exact
 * {@code allocatedAt}/{@code backOrderedSince} timestamp, not the polling interval, is
 * what should drive the latency chart — polling only tells k6 *when to stop asking*.
 */
public record OrderStatusResponse(
    UUID orderId,
    UUID ownerId,
    String externalOrderNo,
    UUID fulfillmentNodeId,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    List<Line> lines,
    String status,
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
        order.getDeliveryTerms().fulfillmentNodeId(),
        order.getDeliveryTerms().shipToZone(),
        order.getDeliveryTerms().shipToAddress(),
        order.getDeliveryTerms().promisedDeliveryDate(),
        order.getLines().stream().map(OrderStatusResponse::toLine).toList(),
        order.getStatus().name(),
        order.getPlacedAt(),
        order.getAllocatedAt(),
        order.getBackOrderedSince(),
        order.getCancelledAt());
  }

  private static Line toLine(OrderLine line) {
    return new Line(line.getLineNo(), line.getSkuCode(), line.getQuantity(),
        line.getStatus().name());
  }
}
