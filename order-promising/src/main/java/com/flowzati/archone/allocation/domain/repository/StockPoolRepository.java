package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockPool;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockPoolRepository {

  Optional<StockPool> findById(UUID id);

  /**
   * 配貨拿得到的那一組批：屬於這個貨主、這個倉、這個 SKU，**今天沒過期且還有量**，依 FEFO
   * 排序。
   *
   * <p><b>「可配」是兩個條件的合取</b>，不只是沒過期：一批全被預留光的貨，對配貨而言與不
   * 存在沒有差別。兩個條件都在這裡而不是散在呼叫端，因為方法名承諾的就是「配得到的」——
   * 少一個條件就名不副實。
   *
   * <p><b>篩選與排序都在資料庫做</b>，不是取回全部再於記憶體裡處理。批數隨營運時間成長，
   * 而過期的批只會累積不會消失——把它們載進記憶體只為了丟掉是錯的方向，且那個順序正好就是
   * index 的順序，資料庫不必額外排序。
   *
   * <p>排序鍵是 {@code (expiry_date, in_date, id)} 三層。後兩層不是裝飾：同效期不同日到貨
   * 很常見，少了它們同效期的批之間順序不定，配貨結果就不可重現，防死鎖的寫入排序也失去
   * 依據。
   */
  List<StockPool> findAllocatableBatchesInFefoOrder(
      UUID ownerId, UUID nodeId, String skuCode, LocalDate today);

  /**
   * 某貨主某 SKU 在**所有倉**的批，順序與配貨一致（倉別、效期、入庫日、id）。
   *
   * <p>供庫存頁使用，因此**不做任何篩選**：過期的、預留光的都要在，且要看得出是哪一種。
   * 濾掉會讓「有 100 件但一件都出不了」與「什麼都沒有」在畫面上長得一樣，而前者要報廢、
   * 後者要進貨。
   */
  List<StockPool> findBatchesAcrossNodes(UUID ownerId, String skuCode);

  /**
   * 依五個身分維度取那一列。
   *
   * <p>補貨用它決定是加到既有列還是新開一列——命中就加、沒命中就開。合併規則因此完全由
   * 鍵決定，沒有另一套邏輯要維護，也就沒有另一套邏輯會與鍵不一致。
   */
  Optional<StockPool> findByIdentity(
      UUID ownerId, UUID nodeId, String skuCode, LocalDate inDate, LocalDate expiryDate);

  int save(StockPool stockPool);
}
