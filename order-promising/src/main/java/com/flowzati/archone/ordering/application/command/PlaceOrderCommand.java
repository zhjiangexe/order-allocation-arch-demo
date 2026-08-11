package com.flowzati.archone.ordering.application.command;

import com.flowzati.archone.ordering.domain.model.DeliveryTerms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 收單命令。
 *
 * <p>以 command 物件取代位置參數:單頭欄位加到六個之後,呼叫端的可讀性會崩潰,而行的
 * 清單也無法用位置參數自然表達。
 *
 * <p>行不帶行號:上游單的行號是要保留的原始結構,但目前的下單入口沒有上游單據,行號由收單
 * 依序產生。之後若接上真正的上游系統,行號改由這裡帶入,{@code order_lines.line_no} 不必
 * 改變。
 *
 * <p><b>不帶收單時刻。</b>那是我們的事實,由 usecase 以系統時鐘寫入——讓呼叫端提供它,等於
 * 讓外部決定我們何時收到一張單,而排序訂單先後靠的正是那個值。
 */
public record PlaceOrderCommand(
    UUID ownerId,
    String externalOrderNo,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    Instant dispatchBy,
    int releasePriority,
    UUID facilityId,
    /** 上游說客戶下單的時刻。可為 {@code null}——上游沒有義務送這個值。 */
    Instant placedAt,
    List<Line> lines
) {

  public record Line(String skuCode, int quantity) {
  }

  public DeliveryTerms toDeliveryTerms() {
    return new DeliveryTerms(
        facilityId,
        shipToZone,
        shipToAddress,
        promisedDeliveryDate,
        dispatchBy,
        releasePriority);
  }
}
