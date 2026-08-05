package com.flowzati.archone.common.outbox;

import java.util.UUID;

/**
 * 會搶同一批庫存的訊息，其 partition key。
 *
 * <p>{@code stock} 分區策略存在的目的，是讓「會搶同一列庫存」的訊息收斂進同一個 partition，
 * 使 consumer 成為那些列的 single writer。
 *
 * <p><b>群組維持在 Facility，不換成位置。</b>庫存改掛在位置上之後，這個鍵仍然以 Facility
 * 分群。一個 Facility 有多個內部位置時，以 Facility 分群會**過度序列化**——那是安全的方向
 * （併發變少、正確性不變），而換成位置卻可能把共用同一把鎖的寫入拆到不同的 writer。
 * <b>粗是安全的，細才危險。</b>它同時是對外事件的 key，而對外的契約說倉。
 *
 * <p><b>群組是 {@code (貨主, 倉)}，刻意不含 SKU。</b>庫存以
 * {@code (貨主, 位置, SKU, 入庫日, 效期)} 唯一，所以含 SKU 的 key 更貼近「哪些列會被碰到」——
 * 但它有一個到期日：一張訂單放寬成多行多 SKU 之後，摺不出單一個 key，而 ship-complete 要求
 * 整籃的 ATP 在同一個交易裡判斷，per-SKU 的 writer 管轄範圍必然被跨越。
 *
 * <p>把 SKU 拿掉就沒有那個到期日：不管一張單跨幾個 SKU，它仍然只屬於一個
 * {@code (貨主, 倉)}，single writer 成立，而且**一個 writer 看得到整張單**——那正是
 * ship-complete 需要的。
 *
 * <p>代價是**過度序列化**：同一個貨主同一個倉、不同 SKU 的訂單本來永遠不會撞（不同的庫存
 * 列），現在也排在同一條隊伍裡。這是刻意選的方向——
 * <b>太粗只是慢但正確，太細則直接失去 single writer</b>（見 {@code outbox-event-delivery}
 * 規格）。而在本專案的量體下慢幾乎收不到：壓測量到「完全沒有 single writer」也只掉約 25%
 * 吞吐，容量餘裕有一個數量級。
 *
 * <p>兩個 UUID 都是定長的，所以直接以分隔字元相接不會有歧義——這也是拿掉 SKU 的附帶好處：
 * 不再需要擔心自由文字的 SKU 代碼含有分隔字元。
 *
 * <p>放在 {@code common} 是因為 ordering 的 translator 與 demo 的可用庫存探針**必須產生逐位元
 * 相同的 key**。各自複製一份的話，其中一邊改了另一邊沒改，兩類訊息就分到不同 partition，
 * 而 single writer 的保證會在沒有任何錯誤訊息的情況下失效。
 */
public final class StockContentionKey {

  private StockContentionKey() {
  }

  public static String of(UUID ownerId, UUID facilityId) {
    if (ownerId == null || facilityId == null) {
      throw new IllegalArgumentException("Owner ID and facility ID are required");
    }
    return ownerId + "/" + facilityId;
  }
}
