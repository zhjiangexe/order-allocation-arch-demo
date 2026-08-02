package com.flowzati.archone.allocation.application.movement;

import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockWriteOrder;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.catalog.domain.model.LocationUsage;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.common.IdGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 搬運的第三個動作：**完成**（Odoo 的 {@code stock.move._action_done}）。
 *
 * <p><b>庫存的數量在這裡才變，而且是由明細去變。</b>取自 Odoo 19：
 * {@code stock.move.line._action_done()} 的註解自己寫著「It'll actually move a quant」，而
 * {@code stock.move._action_done} 只負責篩選與轉狀態。我們的明細已經指向庫存列，形狀本來就
 * 對得上。
 *
 * <p><b>目前只實作「加」那一半。</b>出貨的「減」屬 R7。介面照兩邊都能用的形狀設計（完成就是
 * 完成，差別在搬運的兩端），但沒有呼叫端的那一半**拋錯而不是留一個未測的分支**。
 */
@Component
public class MovementCompleter {

  private final StockMoveRepository stockMoveRepository;
  private final StockPoolRepository stockPoolRepository;
  private final StockLocationRepository stockLocationRepository;

  public MovementCompleter(
      StockMoveRepository stockMoveRepository,
      StockPoolRepository stockPoolRepository,
      StockLocationRepository stockLocationRepository) {
    this.stockMoveRepository = stockMoveRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.stockLocationRepository = stockLocationRepository;
  }

  /**
   * 完成這些搬運：貨真的動了。
   *
   * <p>順序是**找到或開一列庫存 → 建明細 → 轉狀態 → 由明細加數量**。開一列數量為 0 的庫存
   * 再加上去，看起來多此一舉，但它讓兩件事同時成立：明細指向一列真實存在的庫存（外鍵是
   * NOT NULL），而數量只由明細改。
   *
   * <p>舊的補貨有兩條路——找到就加、找不到就用最終數量新建。**第二條正是繞過搬運的那一條。**
   *
   * @param batchIdentity 這批貨的入庫日與效期。它們是庫存列身分的一部分，不是屬性
   */
  public void complete(List<StockMove> moves, BatchIdentity batchIdentity, Instant now) {
    // 以五維身分當鍵，不是以庫存列的 id——**新開的那一列還沒有 id 可以當鍵**，而同一次完成
    // 裡兩段同 SKU 的搬運必須落在同一列上。用 id 當鍵會讓第二段自己再開一列，兩列的五維
    // 完全相同，接著撞 uq_stock_pools_batch。
    Map<String, StockPool> touched = new LinkedHashMap<>();
    List<StockMoveLine> lines = new ArrayList<>();

    for (StockMove move : moves) {
      requireIncoming(move);

      StockPool pool = touched.computeIfAbsent(
          move.getOwnerId() + "|" + move.getToLocationId() + "|" + move.getSkuCode(),
          key -> poolFor(move, batchIdentity));
      StockMoveLine line = new StockMoveLine(
          IdGenerator.nextId(), move.getId(), pool.getId(), move.getDemandQuantity());
      lines.add(line);

      // 先鎖定再完成：狀態與時間戳的 CHECK 要求已完成的搬運說得出它什麼時候拿到貨。收貨
      // 的中間狀態在同一個交易內沒有人看得到——Odoo 的收貨也走完整段，只是它的 assign
      // 那一步「建明細但不動 quant」。
      move.assign(now);
      move.complete(now);
      pool.receive(line);
    }

    touched.values().stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockPoolRepository::save);
    stockMoveRepository.saveAll(moves);
    stockMoveRepository.saveLines(lines);
  }

  /**
   * 這批貨落在哪一列庫存：五維識別，找不到就開一列**空的**。
   *
   * <p>五維（貨主、位置、SKU、入庫日、效期）的規則一個字沒變，變的是誰執行它——從補貨的
   * usecase 搬到這裡，因為「貨落在哪一批」是完成搬運的一部分，不是收到訊息的一部分。
   */
  private StockPool poolFor(StockMove move, BatchIdentity batchIdentity) {
    return stockPoolRepository.findByIdentity(
            move.getOwnerId(), move.getToLocationId(), move.getSkuCode(),
            batchIdentity.inDate(), batchIdentity.expiryDate())
        .orElseGet(() -> new StockPool(
            IdGenerator.nextId(),
            move.getOwnerId(),
            move.getToLocationId(),
            move.getSkuCode(),
            batchIdentity.inDate(),
            batchIdentity.expiryDate(),
            0,
            0,
            null));
  }

  /**
   * 只有進到內部位置的搬運能被完成。
   *
   * <p>出貨（來源是內部位置）要扣的是在手量，而那一半屬 R7。**拋錯而不是留一個未測的分支**：
   * 一個沒有呼叫端、沒有測試的實作會腐爛，而它腐爛的方式是「看起來能用」。
   */
  private void requireIncoming(StockMove move) {
    LocationUsage from = usageOf(move.getFromLocationId());
    if (from == LocationUsage.INTERNAL) {
      throw new IllegalStateException(
          "Completing an outgoing movement is not implemented until shipping exists");
    }
    if (usageOf(move.getToLocationId()) != LocationUsage.INTERNAL) {
      throw new IllegalStateException(
          "A movement that completes must end in an internal location, was " + move.getToLocationId());
    }
  }

  private LocationUsage usageOf(UUID locationId) {
    return stockLocationRepository.findById(locationId)
        .map(StockLocation::getUsage)
        .orElseThrow(() -> new IllegalStateException(
            "Location " + locationId + " no longer exists"));
  }

  /** 這批貨的批次身分。入庫日與效期是庫存列身分的一部分，所以必須由到貨的一方說。 */
  public record BatchIdentity(LocalDate inDate, LocalDate expiryDate) {

    public BatchIdentity {
      if (inDate == null || expiryDate == null) {
        throw new IllegalArgumentException("A batch is identified by its arrival and expiry");
      }
    }
  }
}
